package io.quarkus.deployment.runnerjar;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.Set;

import io.quarkus.bootstrap.model.AppArtifact;
import io.quarkus.bootstrap.model.AppDependency;
import io.quarkus.bootstrap.model.AppModel;
import io.quarkus.bootstrap.resolver.TsArtifact;
import io.quarkus.bootstrap.resolver.TsDependency;
import io.quarkus.bootstrap.resolver.TsQuarkusExt;

public class ExcludedDepsTest extends ExecutableOutputOutcomeTestBase {

    @Override
    protected TsArtifact modelApp() {

        final TsArtifact extADep1 = TsArtifact.jar("ext-a-dep-1");
        final TsArtifact extADep2 = TsArtifact.jar("ext-a-dep-2");
        final TsArtifact extADepTrans1 = TsArtifact.jar("ext-a-dep-trans-1");
        final TsArtifact extADepTrans2 = TsArtifact.jar("ext-a-dep-trans-2");
        final TsArtifact depToExclude1 = TsArtifact.jar("ext-a-dep-exclude-1");
        final TsArtifact depToExclude2 = TsArtifact.jar("ext-a-dep-exclude-2");
        final TsArtifact depToExclude3 = TsArtifact.jar("ext-a-dep-exclude-3");
        final TsArtifact depToExclude4 = TsArtifact.jar("ext-a-dep-exclude-4");
        final TsArtifact depToExclude5 = TsArtifact.jar("ext-a-dep-exclude-5");
        final TsArtifact depToExclude6 = TsArtifact.jar("ext-a-dep-exclude-6");
        extADepTrans2.addDependency(new TsDependency(extADep2));
        extADepTrans2.addDependency(new TsDependency(depToExclude6));
        extADepTrans1.addDependency(new TsDependency(extADepTrans2));
        extADepTrans1.addDependency(new TsDependency(depToExclude2));
        extADepTrans1.addDependency(new TsDependency(depToExclude5));
        extADep1.addDependency(extADepTrans1, depToExclude5);
        extADep1.addDependency(new TsDependency(depToExclude1));

        final TsQuarkusExt extA = new TsQuarkusExt("ext-a");
        addToExpectedLib(extA.getRuntime());
        extA.getRuntime()
                .addDependency(depToExclude3);
        extA.getDeployment()
                .addDependency(extADep1, depToExclude6)
                .addDependency(depToExclude4);

        return TsArtifact.jar("app")
                .addManagedDependency(platformDescriptor())
                .addManagedDependency(platformProperties())
                .addDependency(extA, depToExclude1, depToExclude2, depToExclude3, depToExclude4);
    }

    @Override
    protected void assertAppModel(AppModel appModel) throws Exception {
        final Set<AppDependency> expected = new HashSet<>();
        expected.add(new AppDependency(new AppArtifact("io.quarkus.bootstrap.test", "ext-a-deployment", "1"), "compile"));
        expected.add(new AppDependency(new AppArtifact("io.quarkus.bootstrap.test", "ext-a-dep-1", "1"), "compile"));
        expected.add(new AppDependency(new AppArtifact("io.quarkus.bootstrap.test", "ext-a-dep-2", "1"), "compile"));
        expected.add(new AppDependency(new AppArtifact("io.quarkus.bootstrap.test", "ext-a-dep-trans-1", "1"), "compile"));
        expected.add(new AppDependency(new AppArtifact("io.quarkus.bootstrap.test", "ext-a-dep-trans-2", "1"), "compile"));
        assertEquals(expected, new HashSet<>(appModel.getDeploymentDependencies()));
    }
}
