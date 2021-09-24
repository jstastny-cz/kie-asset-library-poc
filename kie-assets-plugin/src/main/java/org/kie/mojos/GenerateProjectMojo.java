/*
 * Copyright 2021 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kie.mojos;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.invoker.*;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.kie.model.ConfigSet;
import org.kie.model.ProjectDefinition;
import org.kie.model.ProjectStructure;
import org.kie.utils.GeneratedProjectUtils;
import org.kie.utils.MaskedMavenMojoException;
import org.kie.utils.ThrowingBiConsumer;

/**
 * Goal which generates project structure using provided generation method.
 */
@Mojo(name = "generate-project", defaultPhase = LifecyclePhase.PROCESS_SOURCES, requiresProject = false)
public class GenerateProjectMojo
        extends AbstractMojoDefiningParameters {

    public void execute()
            throws MojoExecutionException {
        Path f = outputDirectory.toPath();

        if (!Files.exists(f)) {
            try {
                Files.createDirectories(f);
            } catch (IOException e) {
                throw new MojoExecutionException("Error when creating base directory.", e);
            }
        }

        try {
            generateProjectsAtTargetLocation();
        } catch (MaskedMavenMojoException e) {
            throw new MojoExecutionException("Error while generating projects.", e);
        }
    }

    /**
     * Using {@linkplain AbstractMojoDefiningParameters#getActiveSetup()}
     * generates the directory structure for the current combination of definition and structure.
     */
    private void generateProjectsAtTargetLocation() {
        getLog().info("Importing resources");
        getActiveSetup().apply(generateProjects());
    }

    /**
     * Method that for given definition and structure generates the directory structure for all the active configurations.
     * <p>
     * A BiConsumer implementation to be used together with {@linkplain AbstractMojoDefiningParameters#getActiveSetup()},
     * passed through method {@linkplain AbstractMojoDefiningParameters.ActiveSetup#apply(BiConsumer)}.
     *
     * @return BiConsumer action over {@linkplain ProjectDefinition} and {@linkplain ProjectStructure}.
     */
    private ThrowingBiConsumer generateProjects() {
        return (definition, structure) -> {
            getLog().info("Active definition expressions:" + activeDefinitions);
            getLog().info("Active structure expressions:" + activeStructures);
            getLog().info("About to generate using definition '" + definition.getId() + "' and structure '" + structure.getId() + "'");
            generateProjectBasedOnConfiguration(definition, structure);
            addPomDependencies(definition, structure);
            setFinalNameInPom(definition, structure);
            addPomProperties(definition, structure);
        };
    }

    /**
     * Based on provided configuration decide which project generation method is being used.
     * 
     * @param definition project definition
     * @param structure project structure
     * @throws MavenInvocationException
     * @throws MojoExecutionException
     */
    private void generateProjectBasedOnConfiguration(ProjectDefinition definition, ProjectStructure structure) throws MavenInvocationException, MojoExecutionException {
        switch (structure.getGenerate().getType()) {
            case ARCHETYPE:
                generateFromArchetype(definition, structure);
                break;
            case QUARKUS_CLI:
                generateUsingQuarkusCli(definition, structure);
                break;
            case MAVEN_PLUGIN:
                generateUsingMavenPlugin(definition, structure);
                break;
        }
    }

    /**
     * Generates project using archetype defined in {@linkplain ProjectStructure#getGenerate()} ()} providing properties from
     * {@linkplain ProjectDefinition#getGroupId()}, {@linkplain ProjectDefinition#getArtifactId()}, {@linkplain ProjectDefinition#getPackageName()}
     *
     * @param definition
     * @param projectStructure
     * @throws MavenInvocationException upon maven archetype:generate run failure
     */
    private void generateFromArchetype(ProjectDefinition definition, ProjectStructure projectStructure) throws MavenInvocationException, MojoExecutionException {
        InvocationRequest request = new DefaultInvocationRequest();
        request.setGoals(Collections.singletonList("archetype:generate"));
        request.setUserSettingsFile(mavenSession.getRequest().getUserSettingsFile());
        request.setLocalRepositoryDirectory(mavenSession.getRequest().getLocalRepositoryPath());
        Properties properties = new Properties();
        properties.setProperty("interactiveMode", "false");
        properties.setProperty("groupId", definition.getGroupId());
        properties.setProperty("artifactId", GeneratedProjectUtils.getTargetProjectName(definition, projectStructure));
        properties.setProperty("package", definition.getPackageName());
        properties.setProperty("archetypeVersion", projectStructure.getGenerate().getArchetype().getVersion());
        properties.setProperty("archetypeGroupId", projectStructure.getGenerate().getArchetype().getGroupId());
        properties.setProperty("archetypeArtifactId", projectStructure.getGenerate().getArchetype().getArtifactId());
        properties.putAll(projectStructure.getGenerate().getProperties());
        request.setProperties(properties);
        Invoker invoker = new DefaultInvoker();
        invoker.setWorkingDirectory(outputDirectory);
        InvocationResult result = invoker.execute(request);
        if (result.getExitCode() != 0) {
            throw new MojoExecutionException("Error during archetype generation. See previous errors in log.", result.getExecutionException());
        }
    }

    /**
     * Execute Quarkus CLI command as a process.
     * 
     * @param definition
     * @param structure
     * @throws MojoExecutionException
     */
    private void generateUsingQuarkusCli(ProjectDefinition definition, ProjectStructure structure) throws MojoExecutionException {
        executeCliCommand(getQuarkusCliCreateAppCommand(definition, structure), outputDirectory);
    }

    /**
     * Execute Maven plugin command as a process.
     * 
     * @param definition
     * @param structure
     * @throws MojoExecutionException
     */
    private void generateUsingMavenPlugin(ProjectDefinition definition, ProjectStructure structure) throws MojoExecutionException {
        executeCliCommand(getMavenPluginCreateAppCommand(definition, structure), outputDirectory);
    }

    /**
     * Execute given command in CLI/terminal.
     * 
     * @param command string containing the command
     * @param workDir location where the comamnd is invoked.
     * @throws MojoExecutionException
     */
    private void executeCliCommand(String command, File workDir) throws MojoExecutionException {
        Process process = null;
        try {
            getLog().info("About to execute '" + command + "' in directory " + workDir.getAbsolutePath());
            process = executeProcess(command, workDir);
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                throw new MojoExecutionException("CLI command didn't finish in time.");
            }
            if (process.exitValue() != 0) {
                throw new MojoExecutionException("CLI command ended with state " + process.exitValue());
            }
        } catch (IOException | InterruptedException e) {
            throw new MojoExecutionException("Exception while invoking CLI", e);
        } finally {
            try {
                if (process != null) {
                    collectLogs(getLog()::info, process.getInputStream());
                    collectLogs(getLog()::error, process.getErrorStream());
                }
            } catch (IOException e) {
                throw new MojoExecutionException("Exception while writing logs from CLI", e);
            }
        }
    }

    /**
     * Collecting logs from the running process and passing to provided consumer.
     * 
     * @param consumer
     * @param stream
     * @throws IOException
     */
    private void collectLogs(Consumer<String> consumer, InputStream stream) throws IOException {
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(stream));
        if (bufferedReader.ready()) {
            consumer.accept(bufferedReader.lines().collect(Collectors.joining("\n")).trim());
        }
        bufferedReader.close();
    }

    /**
     * Low-level method to start command as a new process in given directory.
     * 
     * @param command
     * @param workDir
     * @return
     * @throws IOException
     */
    private Process executeProcess(String command, File workDir)
            throws IOException {
        Runtime runtime = Runtime.getRuntime();
        return runtime.exec(command,
                null,
                workDir);
    }

    /**
     * Get string Quarkus CLI command to generate new project based on provided configuration.
     * 
     * @param definition
     * @param projectStructure
     * @return string command
     */
    private String getQuarkusCliCreateAppCommand(ProjectDefinition definition, ProjectStructure projectStructure) {
        Formatter formatter = new Formatter();
        formatter
                .format("%s run quarkus@quarkusio", getJbangExecutable())
                .format(" create app")
                .format(" %s:%s", definition.getGroupId(), GeneratedProjectUtils.getTargetProjectName(definition, projectStructure))
                .format(" -x %s", projectStructure.getGenerate().getQuarkusExtensions())
                .format(" --package-name %s", definition.getPackageName())
                .format(" --batch-mode");
        if (projectStructure.getGenerate().getQuarkusPlatformGav() != null) {
            formatter.format(" --platform-bom %s:%s:%s",
                    projectStructure.getGenerate().getQuarkusPlatformGav().getGroupId(),
                    projectStructure.getGenerate().getQuarkusPlatformGav().getArtifactId(),
                    projectStructure.getGenerate().getQuarkusPlatformGav().getVersion());
        }
        return formatter.toString();
    }

    /**
     * Get path to Jbang executable.
     * 
     * @return string jbang executable
     */
    private String getJbangExecutable() {
        String propertyJbangExecutable = "jbang.executable";
        if (project.getProperties().containsKey(propertyJbangExecutable)) {
            String executable = project.getProperties().get(propertyJbangExecutable).toString();
            getLog().info("Using custom jbang executable '" + executable + "'");
            return executable;
        }
        return "jbang";
    }

    /**
     * Get string maven plugin command to generate new app based on provided configuration.
     * 
     * @param definition
     * @param structure
     * @return string command
     */
    private String getMavenPluginCreateAppCommand(ProjectDefinition definition, ProjectStructure structure) {
        Formatter formatter = new Formatter();
        formatter
                .format("mvn %s:%s:%s:%s",
                        structure.getGenerate().getMavenPluginConfig().getGroupId(),
                        structure.getGenerate().getMavenPluginConfig().getArtifactId(),
                        structure.getGenerate().getMavenPluginConfig().getVersion(),
                        structure.getGenerate().getMavenPluginConfig().getGoal())
                .format(" --batch-mode")
                .format(" -DprojectGroupId=%s", definition.getGroupId())
                .format(" -DprojectArtifactId=%s", GeneratedProjectUtils.getTargetProjectName(definition, structure))
                .format(" -DpackageName=%s", definition.getPackageName());
        if (structure.getGenerate().getQuarkusPlatformGav() != null) {
            formatter
                    .format(" -DplatformGroupId=%s", structure.getGenerate().getQuarkusPlatformGav().getGroupId())
                    .format(" -DplatformArtifactId=%s", structure.getGenerate().getQuarkusPlatformGav().getArtifactId())
                    .format(" -DplatformVersion=%s", structure.getGenerate().getQuarkusPlatformGav().getVersion());
        }
        // append other properties from pom.xml, e.g. noCode, ...
        for (Map.Entry property : structure.getGenerate().getProperties().entrySet()) {
            formatter.format(" -D%s=%s", property.getKey(), property.getValue());
        }
        return formatter.toString();
    }

    /**
     * Manipulate the POM files of generated project. Add dependencies defined by {@linkplain ConfigSet#getDependencies()}.
     * 
     * @param definition project definition to get references from
     * @param structure project structure to get config-set with matching ids from
     * @throws MojoExecutionException on error during file manipulation
     */
    private void addPomDependencies(ProjectDefinition definition, ProjectStructure structure) throws MojoExecutionException {
        Path pomFile = getPathToPom(definition, structure);
        manipulatePom(pomFile, project -> project.getDependencies().addAll(
                resolveActiveConfigSets(definition, structure).stream()
                        .flatMap(it -> it.getDependencies().stream())
                        .collect(Collectors.toList())));
    }

    /**
     * Manipulate the POM files of generated project. Add properties defined by {@linkplain ConfigSet#getProperties()}.
     * 
     * @param definition project definition to get references from
     * @param structure project structure to get config-set with matching ids from
     * @throws MojoExecutionException on error during file manipulation
     */
    private void addPomProperties(ProjectDefinition definition, ProjectStructure structure) throws MojoExecutionException {
        Path pomFile = getPathToPom(definition, structure);
        manipulatePom(pomFile, project -> resolveActiveConfigSets(definition, structure).stream()
                .flatMap(it -> it.getProperties().entrySet().stream())
                .forEach(it -> project.getProperties().put(it.getKey(), it.getValue())));
    }

    /**
     * Allow setting finalName from ProjectDefinition to pom build configuration.
     * 
     * @param definition project definition to take finalName from
     * @param structure project structure to use for pom location resolution
     * @throws MojoExecutionException
     */
    private void setFinalNameInPom(ProjectDefinition definition, ProjectStructure structure) throws MojoExecutionException {
        if (definition.getFinalName() == null || definition.getFinalName().isEmpty()) {
            getLog().debug("No finalName specified, not changing build configuration.");
            return;
        }
        Path pathToPom = getPathToPom(definition, structure);
        manipulatePom(pathToPom, project -> project.getBuild().setFinalName(definition.getFinalName()));
    }

    /**
     * Get path to pom file for given definition : structure pair.
     * 
     * @param definition definition to use for path resolution
     * @param structure structure to use for path resolution
     * @return path to pom.xml
     */
    private Path getPathToPom(ProjectDefinition definition, ProjectStructure structure) {
        Path projectDir = GeneratedProjectUtils.getOutputDirectoryForGeneratedProject(outputDirectory.toPath(), definition, structure);
        Path pomFile = projectDir.resolve("pom.xml");
        return pomFile;
    }

    /**
     * Get Maven Model from given pomFile.
     * 
     * @param pomFile path to pom.xml
     * @return
     * @throws MojoExecutionException
     */
    private Model getPomModel(Path pomFile) throws MojoExecutionException {
        Model model = null;
        try (
                FileInputStream fileReader = new FileInputStream(pomFile.toFile());) {
            MavenXpp3Reader mavenReader = new MavenXpp3Reader();
            model = mavenReader.read(fileReader);
            model.setPomFile(pomFile.toFile());
        } catch (IOException | XmlPullParserException e) {
            throw new MojoExecutionException("Error while opening generated pom: " + pomFile, e);
        }
        return model;
    }

    /**
     * Method that accepts path to pom file and operation to be applied on the MavenProject
     * instance coming from loading it.
     * 
     * @param pathToPom Path to the pom to load and save to after changes.
     * @param manipulator consumer that receives {@linkplain MavenProject} instance.
     * @throws MojoExecutionException when error during manipulation occurs.
     */
    private void manipulatePom(Path pathToPom, Consumer<MavenProject> manipulator) throws MojoExecutionException {
        Model model = getPomModel(pathToPom);
        try (
                FileOutputStream fileWriter = new FileOutputStream(pathToPom.toFile());) {
            MavenProject project = new MavenProject(model);
            manipulator.accept(project);
            MavenXpp3Writer mavenWriter = new MavenXpp3Writer();
            mavenWriter.write(fileWriter, model);
        } catch (IOException e) {
            throw new MojoExecutionException("Error while saving manipulated pom: " + pathToPom, e);
        }
    }
}
