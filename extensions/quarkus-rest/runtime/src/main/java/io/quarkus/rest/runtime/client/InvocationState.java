package io.quarkus.rest.runtime.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.RuntimeType;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.client.ClientResponseFilter;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;

import io.quarkus.rest.api.WebClientApplicationException;
import io.quarkus.rest.runtime.core.Serialisers;
import io.quarkus.rest.runtime.jaxrs.QuarkusRestConfiguration;
import io.quarkus.rest.runtime.jaxrs.QuarkusRestResponse;
import io.quarkus.rest.runtime.util.CaseInsensitiveMap;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;

/**
 * This is a stateful invocation, you can't invoke it twice.
 */
public class InvocationState implements Handler<HttpClientResponse> {

    private final HttpClient httpClient;
    // Changeable by the request filter
    String httpMethod;
    // Changeable by the request filter
    URI uri;
    // Changeable by the request filter
    Entity<?> entity;
    GenericType<?> responseType;
    private boolean responseTypeSpecified;
    private final QuarkusRestClient restClient;
    private final Serialisers serialisers;
    final ClientRequestHeaders requestHeaders;
    private final QuarkusRestConfiguration configuration;
    private final boolean registerBodyHandler;
    // will be used to check if we need to throw a WebApplicationException
    // see Javadoc of javax.ws.rs.client.Invocation or javax.ws.rs.client.SyncInvoker
    private final boolean checkSuccessfulFamily;
    private final CompletableFuture<QuarkusRestResponse> result;
    /**
     * Only initialised if we have request or response filters
     */
    private QuarkusRestClientRequestContext requestContext;
    /**
     * Only initialised once we get the response
     */
    private HttpClientResponse vertxClientResponse;

    public InvocationState(QuarkusRestClient restClient,
            HttpClient httpClient, String httpMethod, URI uri,
            QuarkusRestConfiguration configuration, ClientRequestHeaders requestHeaders, Serialisers serialisers,
            Entity<?> entity, GenericType<?> responseType, boolean registerBodyHandler) {
        this.restClient = restClient;
        this.httpClient = httpClient;
        this.httpMethod = httpMethod;
        this.uri = uri;
        this.requestHeaders = requestHeaders;
        this.configuration = configuration;
        this.serialisers = serialisers;
        this.entity = entity;
        if (responseType == null) {
            this.responseType = new GenericType<>(String.class);
            this.checkSuccessfulFamily = false;
            this.responseTypeSpecified = false;
        } else {
            this.responseType = responseType;
            boolean isJaxResponse = responseType.getRawType().equals(Response.class);
            this.checkSuccessfulFamily = !isJaxResponse;
            this.responseTypeSpecified = !isJaxResponse;
        }
        this.registerBodyHandler = registerBodyHandler;
        this.result = new CompletableFuture<>();
        start();
    }

    private void start() {
        try {
            runRequestFilters();
            if (requestContext != null && requestContext.abortedWith != null) {
                // just run the response filters
                QuarkusRestClientResponseContext context = new QuarkusRestClientResponseContext(
                        requestContext.abortedWith.getStatus(), requestContext.abortedWith.getStatusInfo().getReasonPhrase(),
                        requestContext.abortedWith.getStringHeaders());
                Object abortedWithEntity = requestContext.abortedWith.getEntity();
                if ((abortedWithEntity instanceof String)) {
                    // TODO: probably need to do this for other types as well, but it isn't clear how we would convert the object into bytes, serialization maybe?
                    context.setData(((String) abortedWithEntity).getBytes());
                }
                ensureResponseAndRunFilters(context, abortedWithEntity);
            } else {
                HttpClientRequest httpClientRequest = createRequest();
                Buffer actualEntity = setRequestHeadersAndPrepareBody(httpClientRequest);
                httpClientRequest.handler(this);
                httpClientRequest.exceptionHandler(new Handler<Throwable>() {
                    @Override
                    public void handle(Throwable event) {
                        if (event instanceof IOException) {
                            result.completeExceptionally(new ProcessingException(event));
                        }
                        result.completeExceptionally(event);
                    }
                });
                if (actualEntity == QuarkusRestAsyncInvoker.EMPTY_BUFFER) {
                    httpClientRequest.end();
                } else {
                    httpClientRequest.end(actualEntity);
                }

            }
        } catch (Throwable e) {
            result.completeExceptionally(e);
        }
    }

    private void runRequestFilters() {
        List<ClientRequestFilter> filters = configuration.getRequestFilters();
        if (!filters.isEmpty()) {
            requestContext = new QuarkusRestClientRequestContext(this, restClient, configuration);

            for (ClientRequestFilter filter : filters) {
                try {
                    filter.filter(requestContext);
                } catch (Exception x) {
                    throw new ProcessingException(x);
                }
                if (requestContext.abortedWith != null) {
                    return;
                }
            }
        }
    }

    public <T> T readEntity(Buffer buffer,
            GenericType<T> responseType, MediaType mediaType, MultivaluedMap<String, Object> metadata)
            throws IOException {
        List<MessageBodyReader<?>> readers = serialisers.findReaders(responseType.getRawType(),
                mediaType, RuntimeType.CLIENT);
        for (MessageBodyReader<?> reader : readers) {
            if (reader.isReadable(responseType.getRawType(), responseType.getType(), null,
                    mediaType)) {
                ByteArrayInputStream in = new ByteArrayInputStream(buffer.getBytes());
                return (T) ((MessageBodyReader) reader).readFrom(responseType.getRawType(), responseType.getType(),
                        null, mediaType, metadata, in);
            }
        }

        return (T) buffer.toString(StandardCharsets.UTF_8);
    }

    private QuarkusRestClientResponseContext initialiseResponse(HttpClientResponse vertxResponse) {
        MultivaluedMap<String, String> headers = new CaseInsensitiveMap<>();
        for (String i : vertxResponse.headers().names()) {
            headers.addAll(i, vertxResponse.getHeader(i));
        }
        this.vertxClientResponse = vertxResponse;
        return new QuarkusRestClientResponseContext(vertxResponse.statusCode(), vertxResponse.statusMessage(), headers);
    }

    private <T> Buffer setRequestHeadersAndPrepareBody(HttpClientRequest httpClientRequest)
            throws IOException {
        MultivaluedMap<String, String> headerMap = requestHeaders.asMap();
        for (Map.Entry<String, List<String>> entry : headerMap.entrySet()) {
            httpClientRequest.headers().add(entry.getKey(), entry.getValue());
        }
        if (entity != null && entity.getMediaType() != null) {
            httpClientRequest.headers().set(HttpHeaders.CONTENT_TYPE, entity.getMediaType().toString());
        }
        Buffer actualEntity = QuarkusRestAsyncInvoker.EMPTY_BUFFER;
        if (entity != null) {
            Object entityObject = entity.getEntity();
            Class<?> entityClass;
            Type entityType;
            if (entityObject instanceof GenericEntity) {
                GenericEntity<?> genericEntity = (GenericEntity<?>) entityObject;
                entityClass = genericEntity.getRawType();
                entityType = genericEntity.getType();
                entityObject = genericEntity.getEntity();
            } else {
                entityType = entityClass = entityObject.getClass();
            }
            List<MessageBodyWriter<?>> writers = serialisers.findWriters(entityClass, entity.getMediaType(),
                    RuntimeType.CLIENT);
            for (MessageBodyWriter writer : writers) {
                if (writer.isWriteable(entityClass, entityType, entity.getAnnotations(), entity.getMediaType())) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    writer.writeTo(entityObject, entityClass, entityType, entity.getAnnotations(),
                            entity.getMediaType(), headerMap, baos);
                    actualEntity = Buffer.buffer(baos.toByteArray());
                    break;
                }
            }
        }
        return actualEntity;
    }

    private <T> HttpClientRequest createRequest() {
        HttpClient httpClient = this.httpClient;
        URI uri = this.uri;
        HttpClientRequest httpClientRequest = httpClient.request(HttpMethod.valueOf(httpMethod), uri.getPort(),
                uri.getHost(),
                uri.getPath() + (uri.getQuery() == null ? "" : "?" + uri.getQuery()));

        return httpClientRequest;
    }

    private void ensureResponseAndRunFilters(QuarkusRestClientResponseContext responseContext, Object existingEntity) {
        if (checkSuccessfulFamily && (responseContext.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL)) {
            throw new WebClientApplicationException("Server response status was: " + responseContext.getStatus());
        }

        List<ClientResponseFilter> filters = configuration.getResponseFilters();
        if (!filters.isEmpty()) {
            if (requestContext == null)
                requestContext = new QuarkusRestClientRequestContext(this, restClient, configuration);
            // FIXME: pretty sure we'll have to mark it as immutable in this phase, but the spec is not verbose about this
            // the server does it.
            for (ClientResponseFilter filter : filters) {
                try {
                    filter.filter(requestContext, responseContext);
                } catch (Exception x) {
                    throw new ProcessingException(x);
                }
            }
        }
        QuarkusRestClientResponseBuilder builder = new QuarkusRestClientResponseBuilder();
        builder.status(responseContext.getStatus(), responseContext.getReasonPhrase());
        builder.setAllHeaders(responseContext.getHeaders());
        builder.serializers(serialisers);
        if (existingEntity != null) {
            builder.entity(existingEntity);
        } else {
            if (responseTypeSpecified) { // this case means that a specific response type was requested
                MediaType mediaType = responseContext.getMediaType();
                List<MessageBodyReader<?>> readers = serialisers.findReaders(responseType.getRawType(),
                        mediaType, RuntimeType.CLIENT);
                boolean done = false;
                for (MessageBodyReader<?> reader : readers) {
                    if (reader.isReadable(responseType.getRawType(), responseType.getType(), null,
                            mediaType)) {
                        InputStream in = responseContext.getEntityStream();
                        try {
                            @SuppressWarnings({ "unchecked", "rawtypes" })
                            Object entity = ((MessageBodyReader) reader).readFrom(responseType.getRawType(),
                                    responseType.getType(),
                                    null, mediaType, responseContext.getHeaders(), in);
                            builder.entity(entity);
                        } catch (IOException e) {
                            result.completeExceptionally(e);
                        }
                        done = true;
                        break;
                    }

                }
                if (!done && (responseContext.getData() != null)) {
                    builder.entity(new String(responseContext.getData(), StandardCharsets.UTF_8));
                }
            } else {
                // in this case no specific response type was requested so we just prepare the stream
                // the users of the response are meant to use readEntity
                builder.entityStream(responseContext.getEntityStream());
            }
        }
        result.complete(builder.build());
    }

    @Override
    public void handle(HttpClientResponse clientResponse) {
        try {
            QuarkusRestClientResponseContext context = initialiseResponse(clientResponse);
            if (!registerBodyHandler) {
                ensureResponseAndRunFilters(context, null);
            } else {
                clientResponse.bodyHandler(new Handler<Buffer>() {
                    @Override
                    public void handle(Buffer buffer) {
                        try {
                            context.setData(buffer.getBytes());
                            ensureResponseAndRunFilters(context, null);
                        } catch (Throwable t) {
                            result.completeExceptionally(t);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            result.completeExceptionally(t);
            return;
        }
    }

    public void setEntity(Object entity, Annotation[] annotations, MediaType mediaType) {
        this.entity = Entity.entity(entity, mediaType, annotations);
    }

    public CompletableFuture<QuarkusRestResponse> getResult() {
        return result;
    }

    public HttpClientResponse getVertxClientResponse() {
        return vertxClientResponse;
    }
}