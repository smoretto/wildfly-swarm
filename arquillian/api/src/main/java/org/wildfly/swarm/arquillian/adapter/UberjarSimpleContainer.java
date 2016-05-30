/**
 * Copyright 2015-2016 Red Hat, Inc, and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wildfly.swarm.arquillian.adapter;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.context.ContainerContext;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.resolver.api.maven.MavenResolvedArtifact;
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenCoordinate;
import org.wildfly.swarm.arquillian.WithMain;
import org.wildfly.swarm.arquillian.daemon.DaemonServiceActivator;
import org.wildfly.swarm.arquillian.resolver.ShrinkwrapArtifactResolvingHelper;
import org.wildfly.swarm.bootstrap.util.BootstrapProperties;
import org.wildfly.swarm.msc.ServiceActivatorArchive;
import org.wildfly.swarm.spi.api.JARArchive;
import org.wildfly.swarm.spi.api.SwarmProperties;
import org.wildfly.swarm.tools.BuildTool;
import org.wildfly.swarm.tools.exec.SwarmExecutor;
import org.wildfly.swarm.tools.exec.SwarmProcess;

public class UberjarSimpleContainer implements SimpleContainer {


    private final ContainerContext containerContext;

    public UberjarSimpleContainer(ContainerContext containerContext, Class<?> testClass) {
        this.containerContext = containerContext;
        this.testClass = testClass;
    }

    @Override
    public UberjarSimpleContainer requestedMavenArtifacts(Set<String> artifacts) {
        this.requestedMavenArtifacts = artifacts;

        return this;
    }

    @Override
    public void start(Archive<?> archive) throws Exception {

  /*
        System.err.println( ">>> CORE" );
        System.err.println(" NAME: " + archive.getName());
        for (Map.Entry<ArchivePath, Node> each : archive.getContent().entrySet()) {
            System.err.println("-> " + each.getKey());
        }
        System.err.println( "<<< CORE" );
        */

        //System.err.println("is factory: " + isContainerFactory(this.testClass));

        MainSpecifier mainSpecifier = containerContext.getObjectStore().get(MainSpecifier.class);

        boolean annotatedContainerFactory = false;

        if (isContainerFactory(this.testClass)) {
            System.err.println("is container factory: " + this.testClass.getName());
            archive.as(JavaArchive.class)
                    .addAsServiceProvider("org.wildfly.swarm.ContainerFactory",
                            this.testClass.getName())
                    .addClass(this.testClass);
            archive.as(JARArchive.class).addModule("org.wildfly.swarm.container");
            archive.as(JARArchive.class).addModule("org.wildfly.swarm.configuration");
        } else {
            Method containerMethod = getAnnotatedMethodWithContainer(this.testClass);
            // preflight check it
            if (containerMethod != null) {
                if (Modifier.isStatic(containerMethod.getModifiers())) {
                    // good to go
                    annotatedContainerFactory = true;
                    archive.as(JARArchive.class)
                            .addAsServiceProvider("org.wildfly.swarm.ContainerFactory",
                                    AnnotationBasedContainerFactory.class.getName())
                            .addClass(AnnotationBasedContainerFactory.class)
                            .addClass(Container.class)
                            .addClass(this.testClass);
                    archive.as(JARArchive.class).addModule("org.wildfly.swarm.container");
                    archive.as(JARArchive.class).addModule("org.wildfly.swarm.configuration");
                } else {
                    throw new IllegalArgumentException(
                            String.format("Method annotated with %s is %s but it is not static",
                                    org.wildfly.swarm.arquillian.adapter.Container.class.getSimpleName(),
                                    containerMethod));
                }
            }
        }
        archive.as(ServiceActivatorArchive.class)
                .addServiceActivator(DaemonServiceActivator.class);
        archive.as(JARArchive.class).addModule("org.wildfly.swarm.arquillian.daemon");
        archive.as(JARArchive.class).addModule("org.jboss.modules");
        archive.as(JARArchive.class).addModule("org.jboss.msc");

        BuildTool tool = new BuildTool()
                .projectArchive(archive)
                .fractionDetectionMode(BuildTool.FractionDetectionMode.never)
                .bundleDependencies(false);

        final String additionalModules = System.getProperty(SwarmProperties.BUILD_MODULES);
        if (additionalModules != null) {
            tool.additionalModules(Stream.of(additionalModules.split(":"))
                    .map(File::new)
                    .filter(File::exists)
                    .map(File::getAbsolutePath)
                    .collect(Collectors.toList()));
        }

        final SwarmExecutor executor = new SwarmExecutor().withDefaultSystemProperties();

        if (annotatedContainerFactory) {
            executor.withProperty(AnnotationBasedContainerFactory.ANNOTATED_CLASS_NAME, this.testClass.getName());
        }

        final String additionalRepos = System.getProperty(SwarmProperties.BUILD_REPOS);
        if (additionalRepos != null) {
            executor.withProperty("remote.maven.repo", additionalRepos);
        }

        final ShrinkwrapArtifactResolvingHelper resolvingHelper = ShrinkwrapArtifactResolvingHelper.defaultInstance();
        tool.artifactResolvingHelper(resolvingHelper);

        boolean hasRequestedArtifacts = this.requestedMavenArtifacts != null && this.requestedMavenArtifacts.size() > 0;

        if (!hasRequestedArtifacts) {
            final MavenResolvedArtifact[] deps =
                    resolvingHelper.withResolver(r -> r.loadPomFromFile("pom.xml")
                            .importRuntimeAndTestDependencies()
                            .resolve()
                            .withTransitivity()
                            .asResolvedArtifact());

            for (MavenResolvedArtifact dep : deps) {
                MavenCoordinate coord = dep.getCoordinate();
                tool.dependency(dep.getScope().name(), coord.getGroupId(),
                        coord.getArtifactId(), coord.getVersion(),
                        coord.getPackaging().getExtension(), coord.getClassifier(), dep.asFile());
            }
        } else {
            // ensure that arq daemon is available
            this.requestedMavenArtifacts.add("org.wildfly.swarm:arquillian-daemon");
            for (String requestedDep : this.requestedMavenArtifacts) {
                final MavenResolvedArtifact[] deps =
                        resolvingHelper.withResolver(r -> r.loadPomFromFile("pom.xml")
                                .resolve(requestedDep)
                                .withTransitivity()
                                .asResolvedArtifact());

                for (MavenResolvedArtifact dep : deps) {
                    MavenCoordinate coord = dep.getCoordinate();
                    tool.dependency(dep.getScope().name(), coord.getGroupId(),
                            coord.getArtifactId(), coord.getVersion(),
                            coord.getPackaging().getExtension(), coord.getClassifier(), dep.asFile());
                }
            }
        }

        final String debug = System.getProperty(BootstrapProperties.DEBUG_PORT);
        if (debug != null) {
            try {
                executor.withDebug(Integer.parseInt(debug));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(String.format("Failed to parse %s of \"%s\"", BootstrapProperties.DEBUG_PORT, debug),
                        e);
            }
        }

        if (mainSpecifier != null) {
            tool.mainClass(mainSpecifier.getClassName());
            String[] args = mainSpecifier.getArgs();

            for (String arg : args) {
                executor.withArgument(arg);
            }
        } else {
            WithMain withMainAnno = this.testClass.getAnnotation(WithMain.class);
            if (withMainAnno != null) {
                tool.mainClass(withMainAnno.value().getName());
            }
        }

        Archive<?> wrapped = tool.build();

        if (BootstrapProperties.flagIsSet(SwarmProperties.EXPORT_UBERJAR)) {
            final File out = new File(wrapped.getName());
            System.err.println("Exporting swarm jar to " + out.getAbsolutePath());
            wrapped.as(ZipExporter.class).exportTo(out, true);
        }

        /* for (Map.Entry<ArchivePath, Node> each : wrapped.getContent().entrySet()) {
                System.err.println("-> " + each.getKey());
            }*/

        File executable = File.createTempFile("arquillian", "-swarm.jar");
        wrapped.as(ZipExporter.class).exportTo(executable, true);
        executable.deleteOnExit();


        executor.withProperty("java.net.preferIPv4Stack", "true");
        executor.withExecutableJar(executable.toPath());

        File workingDirectory = Files.createTempDirectory("arquillian").toFile();
        workingDirectory.deleteOnExit();
        executor.withWorkingDirectory(workingDirectory.toPath());

        this.process = executor.execute();
        this.process.getOutputStream().close();

        this.process.awaitDeploy(2, TimeUnit.MINUTES);

        if (!this.process.isAlive()) {
            throw new DeploymentException("Process failed to start");
        }
        if (this.process.getError() != null) {
            throw new DeploymentException("Error starting process", this.process.getError());
        }
    }

    @Override
    public void stop() throws Exception {
        this.process.stop();
    }

    private String ga(final MavenCoordinate coord) {
        return String.format("%s:%s", coord.getGroupId(), coord.getArtifactId());
    }

    private String gav(final MavenCoordinate coord) {
        return gav(coord.getGroupId(), coord.getArtifactId(), coord.getVersion());
    }

    private String gav(final String group, final String artifact, final String version) {
        return String.format("%s:%s:%s", group, artifact, version);
    }

    private final Class<?> testClass;

    private SwarmProcess process;

    private Set<String> requestedMavenArtifacts;


}

