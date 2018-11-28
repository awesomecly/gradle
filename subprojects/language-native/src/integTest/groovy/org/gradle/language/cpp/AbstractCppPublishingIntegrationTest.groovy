/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.language.cpp

import org.gradle.language.nativeplatform.internal.Dimensions
import org.gradle.nativeplatform.fixtures.AbstractInstalledToolChainIntegrationSpec
import org.gradle.nativeplatform.fixtures.ExecutableFixture
import org.gradle.test.fixtures.file.TestFile

abstract class AbstractCppPublishingIntegrationTest extends AbstractInstalledToolChainIntegrationSpec {

    abstract int getVariantCount(List<Map<String,String>> targetMachines)
    abstract List<String> getLinkages()
    abstract List<String> getMainModuleArtifacts(String module, String version)
    abstract List<String> getVariantModuleArtifacts(String variantModuleNameWithVersion)
    abstract TestFile getVariantSourceFile(String module, Map<String, VariantDimension> variantContext)
    abstract Map<String, String> getVariantFileInformation(String linkage, String module, String variantModuleNameWithVersion)
    abstract boolean publishesArtifactForLinkage(String linkage)

    void assertMainModuleIsPublished(String group, String module, String version, List<Map<String, String>> targetMachines, List<String> apiDependencies = []) {
        def mainModule = mavenRepo.module(group, module, version)
        mainModule.assertArtifactsPublished(getMainModuleArtifacts(module, version))
        assert mainModule.parsedPom.scopes.size() == apiDependencies.isEmpty() ? 0 : 1
        if (!apiDependencies.isEmpty()) {
            mainModule.parsedPom.scopes.runtime.assertDependsOn(apiDependencies as String[])
        }

        def mainMetadata = mainModule.parsedModuleMetadata

        if (!apiDependencies.isEmpty()) {
            def mainApi = mainMetadata.variant("api")
            mainApi.dependencies.size() == apiDependencies.size()
            apiDependencies.eachWithIndex { dependency, index ->
                def coordinates = dependency.split(':')
                assert mainApi.dependencies[index].group == coordinates[0]
                assert mainApi.dependencies[index].module == coordinates[1]
                assert mainApi.dependencies[index].version == coordinates[2]
            }
        }

        assert mainMetadata.variants.size() == getVariantCount(targetMachines)
        ['debug', 'release'].each { buildType ->
            linkages.each { linkage ->
                targetMachines.each { machine ->
                    String architectureNormalized = Dimensions.createDimensionSuffix(machine.architecture, targetMachines.collect { it.architecture }.unique())
                    String osFamilyNormalized = Dimensions.createDimensionSuffix(machine.os, targetMachines.collect { it.os }.unique())
                    assert mainMetadata.variant("${buildType}${osFamilyNormalized.capitalize()}${architectureNormalized.capitalize()}${linkage.capitalize()}").availableAt.coords == "${group}:${module}_${buildType}${osFamilyNormalized.empty ? "" : "_${osFamilyNormalized.toLowerCase()}"}${architectureNormalized.empty ? "" : "_${architectureNormalized.toLowerCase().replace("-", "_")}"}:${version}"
                }
            }
        }
    }

    void assertVariantIsPublished(String group, String module, String version, Map<String, VariantDimension> variantContext, List<String> dependencies = []) {
        def buildType = variantContext.buildType
        def architecture = variantContext.architecture
        def operatingSystem = variantContext.os
        String variantModuleName = "${module}${buildType.asPublishingName}${operatingSystem.asPublishingName}${architecture.asPublishingName}"
        String variantModuleNameWithVersion = "${variantModuleName}-${version}"
        def publishedModule = mavenRepo.module(group, variantModuleName, version)
        publishedModule.assertPublished()
        publishedModule.assertArtifactsPublished(getVariantModuleArtifacts(variantModuleNameWithVersion))
        publishedModule.artifactFile(type: getVariantFileInformation('Runtime', module, variantModuleNameWithVersion).extension).assertIsCopyOf(getVariantSourceFile(module, variantContext))

        assert publishedModule.parsedPom.scopes.size() == dependencies.isEmpty() ? 0 : 1
        if (!dependencies.isEmpty()) {
            publishedModule.parsedPom.scopes.runtime.assertDependsOn(dependencies as String[])
        }

        def publishedMetadata = publishedModule.parsedModuleMetadata
        assert publishedMetadata.variants.size() == linkages.size()
        linkages.each { linkage ->
            def publishedVariant = publishedMetadata.variant("${buildType.name}${operatingSystem.asVariantName}${architecture.asVariantName}${linkage}")
            assert publishedVariant.dependencies.size() == dependencies.size()
            publishedVariant.dependencies.eachWithIndex { dependency, int i ->
                assert dependency.coords == dependencies[i]
            }

            if (publishesArtifactForLinkage(linkage)) {
                def variantFileInfo = getVariantFileInformation(linkage, module, variantModuleNameWithVersion)
                assert publishedVariant.files.size() == 1
                assert publishedVariant.files[0].name == variantFileInfo.name
                assert publishedVariant.files[0].url == variantFileInfo.url
            }
        }
    }

    void assertVariantsArePublished(String group, String module, String version, List<String> buildTypes, List<Map<String, VariantDimension>> targetMachines, List<String> dependencies = []) {
        buildTypes.each { buildType ->
            targetMachines.findAll { it.os == currentOsFamilyName }.each { machine ->
                def variantContext = [
                        buildType: VariantDimension.of(buildType)
                ].withDefault {VariantDimension.missing()}
                if (targetMachines.collect({ it.os }).unique().size() > 1) {
                    variantContext.os = VariantDimension.of(machine.os)
                }
                if (targetMachines.collect({ it.architecture }).unique().size() > 1) {
                    variantContext.architecture = VariantDimension.of(machine.architecture)
                }
                assertVariantIsPublished(group, module, version, variantContext, dependencies)
            }
        }
    }

    static abstract class VariantDimension {
        abstract String getAsPath()
        abstract String getAsPublishingName()
        abstract String getAsVariantName()
        abstract String getName()

        static VariantDimension missing() {
            return new MissingVariantDimension()
        }

        static VariantDimension of(String dimensionName) {
            return new DefaultVariantDimension(dimensionName.toLowerCase())
        }

        static class DefaultVariantDimension extends VariantDimension {
            private final String normalizedDimensionName

            DefaultVariantDimension(String normalizedDimensionName) {
                this.normalizedDimensionName = normalizedDimensionName
            }

            @Override
            String getAsPath() {
                return "/${normalizedDimensionName}"
            }

            @Override
            String getAsPublishingName() {
                return "_${normalizedDimensionName.replace("-", "_")}"
            }

            @Override
            String getAsVariantName() {
                return normalizedDimensionName.capitalize()
            }

            @Override
            String getName() {
                return normalizedDimensionName
            }
        }

        static class MissingVariantDimension extends VariantDimension {
            final String asPath = ""
            final String asPublishingName = ""
            final String asVariantName = ""
            final String name = ""
        }
    }

    @Override
    ExecutableFixture executable(Object path) {
        ExecutableFixture executable = super.executable(path)
        // Executables synced from a binary repo lose their executable bit
        executable.file.setExecutable(true)
        executable
    }

    Map<String, String> machine(String os, String architecture) {
        return ["os": os, "architecture": architecture]
    }
}
