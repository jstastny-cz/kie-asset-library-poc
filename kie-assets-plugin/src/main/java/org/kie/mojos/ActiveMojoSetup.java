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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.kie.model.ConfigSet;
import org.kie.model.ProjectDefinition;
import org.kie.model.ProjectStructure;

/**
 * Helper class to iterate over all {@linkplain ProjectDefinition} and {@linkplain ProjectStructure} instances and filter
 * just those that are activated by {@linkplain #activeDefinitions} and {@linkplain #activeStructures}.
 */
public class ActiveMojoSetup {
    private List<ProjectDefinition> projectDefinitions;
    private List<ProjectStructure> projectStructures;
    private List<ConfigSet> reusableConfigSets;
    private Set<String> activeDefinitions;
    private Set<String> activeStructures;
    private Set<String> activeConfigSets;

    public List<ProjectDefinition> getProjectDefinitions() {
        return projectDefinitions;
    }

    public ActiveMojoSetup setProjectDefinitions(List<ProjectDefinition> projectDefinitions) {
        this.projectDefinitions = projectDefinitions;
        return this;
    }

    public List<ProjectStructure> getProjectStructures() {
        return projectStructures;
    }

    public ActiveMojoSetup setProjectStructures(List<ProjectStructure> projectStructures) {
        this.projectStructures = projectStructures;
        return this;
    }

    public Set<String> getActiveDefinitions() {
        return activeDefinitions;
    }

    public ActiveMojoSetup setActiveDefinitions(Set<String> activeDefinitions) {
        this.activeDefinitions = activeDefinitions;
        return this;
    }

    public Set<String> getActiveStructures() {
        return activeStructures;
    }

    public ActiveMojoSetup setActiveStructures(Set<String> activeStructures) {
        this.activeStructures = activeStructures;
        return this;
    }

    public List<ConfigSet> getReusableConfigSets() {
        return reusableConfigSets;
    }

    public ActiveMojoSetup setReusableConfigSets(List<ConfigSet> reusableConfigSets) {
        this.reusableConfigSets = reusableConfigSets;
        return this;
    }

    public Set<String> getActiveConfigSets() {
        return activeConfigSets;
    }

    public ActiveMojoSetup setActiveConfigSets(Set<String> activeConfigSets) {
        this.activeConfigSets = activeConfigSets;
        return this;
    }

    /**
     * BiConsumer which gets combinations of all activated {@linkplain ProjectDefinition} and {@linkplain ProjectStructure}.
     *
     * @param action a consumer to accept the active configurations.
     */
    public void apply(BiConsumer<ProjectDefinition, ProjectStructure> action) {
        for (ProjectDefinition definition : projectDefinitions) {
            if (AbstractMojoDefiningParameters.isDefinitionActive(activeDefinitions, definition)) {
                for (ProjectStructure structure : projectStructures) {
                    if (AbstractMojoDefiningParameters.isStructureActive(activeStructures, structure))
                        action.accept(definition, structure);
                }
            }
        }
    }

    /**
     * Resolve configSets {@linkplain ConfigSet} specified by one of:
     * <ul>
     * <li>{@linkplain ProjectDefinition#getConfig()}</li>
     * <li>{@linkplain ProjectStructure#getCommonConfig()}</li>
     * <li>{@linkplain ProjectStructure#getConfigSets()}
     * with {@linkplain ConfigSet#getId()} matching one of {@linkplain #activeConfigSets}</li>
     * </ul>
     *
     * It also supports {@linkplain ConfigSet#getReusableConfig()} reference to a globally pre-defined configSet using
     * {@linkplain AbstractMojoDefiningParameters#reusableConfigSets} configuration.
     */
    public BiFunction<ProjectDefinition, ProjectStructure, List<ConfigSet>> getActiveConfigSetResolver() {
        return (definition, structure) -> {
            List<ConfigSet> applicableConfig = new ArrayList<>();
            applicableConfig.add(definition.getConfig());
            applicableConfig.add(structure.getCommonConfig());
            applicableConfig.addAll(
                    structure.getConfigSets().stream()
                            .filter(it -> activeConfigSets.contains(it.getId())).collect(Collectors.toList()));
            return applicableConfig.stream().map(it -> {
                if (it.getReusableConfig() != null) {
                    return reusableConfigSets.stream()
                            .filter(
                                    reusable -> reusable.getId().equals(it.getReusableConfig()))
                            .findFirst().orElseThrow(() -> new RuntimeException("ConfigSet " + it.getReusableConfig() + " not found among reusable config sets."));
                } else {
                    return it;
                }
            }).collect(Collectors.toList());
        };
    }
}
