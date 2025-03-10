/*
 *  Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package io.ballerina.projects;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import io.ballerina.projects.environment.PackageCache;
import io.ballerina.projects.internal.bala.BalaJson;
import io.ballerina.projects.internal.bala.DependencyGraphJson;
import io.ballerina.projects.internal.bala.ModuleDependency;
import io.ballerina.projects.internal.bala.PackageJson;
import io.ballerina.projects.internal.bala.adaptors.JsonCollectionsAdaptor;
import io.ballerina.projects.internal.bala.adaptors.JsonStringsAdaptor;
import io.ballerina.projects.internal.model.BalToolDescriptor;
import io.ballerina.projects.internal.model.CompilerPluginDescriptor;
import io.ballerina.projects.internal.model.Dependency;
import io.ballerina.projects.util.ProjectConstants;
import io.ballerina.projects.util.ProjectUtils;
import io.ballerina.tools.text.TextDocument;
import io.ballerina.tools.text.TextDocuments;
import org.apache.commons.compress.utils.IOUtils;
import org.ballerinalang.compiler.BLangCompilerException;
import org.wso2.ballerinalang.util.RepoUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

import static io.ballerina.projects.util.ProjectConstants.BALA_DOCS_DIR;
import static io.ballerina.projects.util.ProjectConstants.BALA_JSON;
import static io.ballerina.projects.util.ProjectConstants.DEPENDENCY_GRAPH_JSON;
import static io.ballerina.projects.util.ProjectConstants.PACKAGE_JSON;
import static io.ballerina.projects.util.ProjectUtils.getBalaName;
import static io.ballerina.projects.util.ProjectUtils.getConflictingResourcesMsg;

/**
 * {@code BalaWriter} writes a package to bala format.
 *
 * @since 2.0.0
 */
public abstract class BalaWriter {
    private static final String MODULES_ROOT = "modules";
    private static final String RESOURCE_DIR_NAME = "resources";
    private static final String BLANG_SOURCE_EXT = ".bal";
    protected static final String PLATFORM = "platform";
    protected static final String PATH = "path";
    private static final String MAIN_BAL = "main.bal";
    private static final String UNIX_FILE_SEPARATOR = "/";

    // Set the target as any for default bala.
    protected String target = "any";
    private static final String IMPLEMENTATION_VENDOR = "WSO2";
    private static final String BALLERINA_SHORT_VERSION = RepoUtils.getBallerinaShortVersion();
    private static final String BALLERINA_SPEC_VERSION = RepoUtils.getBallerinaSpecVersion();
    protected PackageContext packageContext;
    Optional<CompilerPluginDescriptor> compilerPluginToml;
    protected Optional<BalToolDescriptor> balToolToml;

    protected BalaWriter() {
    }

    /**
     * Write a package to a .bala and return the created .bala path.
     *
     * @param balaPath Directory where the .bala should be created.
     */
    public Path write(Path balaPath) {
        String balaName = getBalaName(this.packageContext.packageOrg().value(),
                                      this.packageContext.packageName().value(),
                                      this.packageContext.packageVersion().value().toString(),
                                      this.target);
        // Create the archive overwrite if exists
        try (ZipOutputStream balaOutputStream = new ZipOutputStream(
                new FileOutputStream(String.valueOf(balaPath.resolve(balaName))))) {
            // Now lets put stuff in
            populateBalaArchive(balaOutputStream);
        } catch (IOException e) {
            throw new ProjectException("Failed to create bala :" + e.getMessage(), e);
        } catch (BLangCompilerException be) {
            // clean up if an error occur
            try {
                Files.delete(balaPath);
            } catch (IOException e) {
                // We ignore this error and throw out the original blang compiler error to the user
            }
            throw be;
        }
        return balaPath.resolve(balaName);
    }

    private void populateBalaArchive(ZipOutputStream balaOutputStream)
            throws IOException {

        addBalaJson(balaOutputStream);
        addPackageDoc(balaOutputStream,
                      this.packageContext.packageManifest());
        addPackageSource(balaOutputStream);
        addResources(balaOutputStream);
        addIncludes(balaOutputStream);
        Optional<JsonArray> platformLibs = addPlatformLibs(balaOutputStream);
        addPackageJson(balaOutputStream, platformLibs);

        addCompilerPlugin(balaOutputStream);
        addBalTool(balaOutputStream);
        addDependenciesJson(balaOutputStream);
    }

    private void addBalaJson(ZipOutputStream balaOutputStream) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String balaJson = gson.toJson(new BalaJson());
        try {
            putZipEntry(balaOutputStream, Path.of(BALA_JSON),
                    new ByteArrayInputStream(balaJson.getBytes(Charset.defaultCharset())));
        } catch (IOException e) {
            throw new ProjectException("Failed to write 'bala.json' file: " + e.getMessage(), e);
        }
    }

    private void addPackageJson(ZipOutputStream balaOutputStream, Optional<JsonArray> platformLibs) {
        PackageJson packageJson = new PackageJson(this.packageContext.packageOrg().toString(),
                                                  this.packageContext.packageName().toString(),
                                                  this.packageContext.packageVersion().toString());

        PackageManifest packageManifest = this.packageContext.packageManifest();
        packageJson.setLicenses(packageManifest.license());
        packageJson.setAuthors(packageManifest.authors());
        packageJson.setSourceRepository(packageManifest.repository());
        packageJson.setKeywords(packageManifest.keywords());
        packageJson.setExport(packageManifest.exportedModules());
        packageJson.setInclude(packageManifest.includes());
        packageJson.setVisibility(packageManifest.visibility());
        packageJson.setTemplate(packageManifest.template());
        packageJson.setDescription(packageManifest.description());

        packageJson.setPlatform(target);
        packageJson.setBallerinaVersion(BALLERINA_SHORT_VERSION);
        packageJson.setLanguageSpecVersion(BALLERINA_SPEC_VERSION);
        packageJson.setImplementationVendor(IMPLEMENTATION_VENDOR);

        platformLibs.ifPresent(packageJson::setPlatformDependencies);

        // Set icon in bala path in the package.json
        if (packageManifest.icon() != null && !packageManifest.icon().isEmpty()) {
            Path iconPath = getIconPath(packageManifest.icon());
            packageJson.setIcon(BALA_DOCS_DIR + UNIX_FILE_SEPARATOR + iconPath.getFileName());
        }
        // Set graalvmCompatibility property in package.json
        setGraalVMCompatibilityProperty(packageJson, packageManifest);

        setReadme(packageManifest, packageJson);
        setModules(packageJson, packageManifest);

        // Remove fields with empty values from `package.json`
        Gson gson = new GsonBuilder().registerTypeHierarchyAdapter(Collection.class, new JsonCollectionsAdaptor())
                .registerTypeHierarchyAdapter(String.class, new JsonStringsAdaptor()).setPrettyPrinting().create();

        try {
            putZipEntry(balaOutputStream, Path.of(PACKAGE_JSON),
                    new ByteArrayInputStream(gson.toJson(packageJson).getBytes(Charset.defaultCharset())));
        } catch (IOException e) {
            throw new ProjectException("Failed to write 'package.json' file: " + e.getMessage(), e);
        }
    }

    private void setReadme(PackageManifest packageManifest, PackageJson packageJson) {
        if (packageManifest.readme() != null) { // Null check is required for ballerinai packages
            packageJson.setReadme(BALA_DOCS_DIR + UNIX_FILE_SEPARATOR +
                    Paths.get(packageManifest.readme()).getFileName());
        }
    }

    private void setModules(PackageJson packageJson, PackageManifest packageManifest) {
        List<PackageManifest.Module> modules = new ArrayList<>();
        String packageDocPathPrefix = BALA_DOCS_DIR + UNIX_FILE_SEPARATOR;

        List<PackageManifest.Module> moduleList = packageManifest.modules();
        for (PackageManifest.Module module : moduleList) {
            String moduleDoc = null;
            if (module.readme() != null && !module.readme().isEmpty()) {
                moduleDoc = packageDocPathPrefix + MODULES_ROOT + UNIX_FILE_SEPARATOR + module.name() +
                        UNIX_FILE_SEPARATOR + Paths.get(module.readme()).getFileName();
            }
            modules.add(new PackageManifest.Module(
                    module.name(),
                    module.export(),
                    module.description(),
                    moduleDoc));
        }
        packageJson.setModules(modules);
    }

    private void setGraalVMCompatibilityProperty(PackageJson packageJson, PackageManifest packageManifest) {
        Map<String, PackageManifest.Platform> platforms = packageManifest.platforms();
        Boolean allPlatformDepsGraalvmCompatible = isAllPlatformDepsGraalvmCompatible(packageManifest.platforms());
        PackageManifest.Platform targetPlatform = packageManifest.platform(target);
        if (platforms != null) {
            if (targetPlatform != null) {
                Boolean graalvmCompatible = targetPlatform.graalvmCompatible();
                if (graalvmCompatible != null) {
                    // If the package explicitly specifies the graalvmCompatibility property, then use it unless
                    // no individual dependency is incompatible
                    boolean finalCompatibility = (allPlatformDepsGraalvmCompatible != null) ?
                            (allPlatformDepsGraalvmCompatible && graalvmCompatible) : graalvmCompatible;
                    packageJson.setGraalvmCompatible(finalCompatibility);
                    return;
                }
            }
            if (!otherPlatformGraalvmCompatibleVerified(target, packageManifest.platforms()).isEmpty()) {
                Boolean otherGraalvmCompatible = packageManifest.platform(otherPlatformGraalvmCompatibleVerified(target,
                        packageManifest.platforms())).graalvmCompatible();
                boolean finalCompatibility = (allPlatformDepsGraalvmCompatible != null) ?
                        (allPlatformDepsGraalvmCompatible && otherGraalvmCompatible) : otherGraalvmCompatible;
                packageJson.setGraalvmCompatible(finalCompatibility);
                return;
            }
            // If the package uses only distribution provided platform libraries, then package is graalvm compatible
            // If platform libraries are specified with 'graalvmCompatible', infer the overall compatibility.
            packageJson.setGraalvmCompatible(allPlatformDepsGraalvmCompatible);
        } else {
            // If the package uses only distribution provided platform libraries
            // or has only ballerina dependencies, then the package is graalvm compatible
            packageJson.setGraalvmCompatible(true);
        }
    }

    private static Boolean isAllPlatformDepsGraalvmCompatible(Map<String, PackageManifest.Platform> platforms) {
        Boolean isAllDepsGraalvmCompatible = true;
        for (PackageManifest.Platform platform: platforms.values()) {
            if (platform.isPlatfromDepsGraalvmCompatible() == null) {
                isAllDepsGraalvmCompatible = null;
            } else if (!platform.isPlatfromDepsGraalvmCompatible()) {
                return false;
            }
        }
        return isAllDepsGraalvmCompatible;
    }

    private String otherPlatformGraalvmCompatibleVerified(String target,
                                                                 Map<String, PackageManifest.Platform> platforms) {
        for (Map.Entry<String, PackageManifest.Platform> platform : platforms.entrySet()) {
            if (!platform.getKey().equals(target) && platform.getValue().graalvmCompatible() != null) {
                return platform.getKey();
            }
        }
        return "";
    }

    // TODO when iterating and adding source files should create source files from Package sources

    private void addPackageDoc(ZipOutputStream balaOutputStream, PackageManifest packageManifest)
            throws IOException {

        if (packageManifest.readme() == null) {
            return;
        }
        Path sourceRoot = this.packageContext.project().sourceRoot;
        Path pkgReadme = Paths.get(packageManifest.readme());
        Path docsDirInBala = Path.of(BALA_DOCS_DIR);

        Path packageMdInBala = docsDirInBala.resolve(pkgReadme.getFileName());
        putZipEntry(balaOutputStream, packageMdInBala,
                new FileInputStream(pkgReadme.toString()));

        // If `icon` mentioned in the Ballerina.toml, add it to docs directory
        String icon = this.packageContext.packageManifest().icon();
        if (icon != null && !icon.isEmpty()) {
            Path iconPath = getIconPath(icon);
            Path iconInBala = docsDirInBala.resolve(iconPath.getFileName());
            putZipEntry(balaOutputStream, iconInBala, new FileInputStream(String.valueOf(iconPath)));
        }

        Path modulesDirInBalaDocs = docsDirInBala.resolve(MODULES_ROOT);

        for (PackageManifest.Module module : packageManifest.modules()) {
            if (module.readme() == null || module.readme().isEmpty()) {
                continue;
            }
            Path otherReadmeMdInBalaDocs = modulesDirInBalaDocs.resolve(module.name())
                    .resolve(Paths.get(module.readme()).getFileName());
            putZipEntry(balaOutputStream, otherReadmeMdInBalaDocs,
                    new FileInputStream(sourceRoot.resolve(module.readme()).toString()));
        }
    }

    private void addPackageSource(ZipOutputStream balaOutputStream) throws IOException {
        // add module sources
        for (ModuleId moduleId : this.packageContext.moduleIds()) {
            Module module = this.packageContext.project().currentPackage().module(moduleId);

            // Generate empty bal file for default module in tools
            if (module.isDefaultModule() && packageContext.balToolTomlContext().isPresent() &&
                    module.documentIds().isEmpty()) {
                String emptyBalContent = """
                        // AUTO-GENERATED FILE.

                        // This file is auto-generated by Ballerina for packages with empty default modules.\s
                        """;

                TextDocument emptyBalTextDocument = TextDocuments.from(emptyBalContent);
                DocumentId documentId = DocumentId.create(MAIN_BAL, moduleId);
                DocumentConfig documentConfig = DocumentConfig.from(documentId, emptyBalTextDocument.toString(),
                        MAIN_BAL);
                module = module.modify().addDocument(documentConfig).apply();
            }

            // only add .bal files of module
            for (DocumentId docId : module.documentIds()) {
                Document document = module.document(docId);
                if (document.name().endsWith(BLANG_SOURCE_EXT)) {
                    Path documentPath = Path.of(MODULES_ROOT, module.moduleName().toString(), document.name());
                    char[] documentContent = document.textDocument().toCharArray();

                    putZipEntry(balaOutputStream, documentPath,
                                new ByteArrayInputStream(new String(documentContent).getBytes(StandardCharsets.UTF_8)));
                }
            }
        }
    }

    private void addResources(ZipOutputStream balaOutputStream) throws IOException {
        Set<String> resourceFiles = new HashSet<>();

        // copy resources
        for (DocumentId documentId : packageContext.resourceIds()) {
            String resourceFile = packageContext.resourceContext(documentId).name();
            Path resourcePath = Path.of(RESOURCE_DIR_NAME).resolve(resourceFile);
            if (resourceFiles.add(resourcePath.toString())) {
                putZipEntry(balaOutputStream, resourcePath, new ByteArrayInputStream(
                        packageContext.resourceContext(documentId).content()));
            }
        }

        // copy resources from `target/resources`
        if (packageContext.project().kind().equals(ProjectKind.BUILD_PROJECT)) {
            Map<String, byte[]> cachedResources = ProjectUtils.getAllGeneratedResources(
                    packageContext.project().generatedResourcesDir());
            List<String> conflictingResourceFiles = cachedResources.keySet().stream()
                    .filter(path -> !resourceFiles.add(path))
                    .collect(Collectors.toList());

            if (!conflictingResourceFiles.isEmpty()) {
                throw new ProjectException(getConflictingResourcesMsg(
                        packageContext.descriptor().toString(), conflictingResourceFiles));
            }

            for (Map.Entry<String, byte[]> entry : cachedResources.entrySet()) {
                putZipEntry(balaOutputStream, Path.of(entry.getKey()), new ByteArrayInputStream(entry.getValue()));
            }
        }
    }

    private void addIncludes(ZipOutputStream balaOutputStream) throws IOException {
        List<String> includePatterns = this.packageContext.packageManifest().includes();
        List<Path> includePaths = ProjectUtils.getPathsMatchingIncludePatterns(
                includePatterns, this.packageContext.project().sourceRoot());

        for (Path includePath: includePaths) {
            Path includePathInPackage = this.packageContext.project().sourceRoot().resolve(includePath)
                    .toAbsolutePath();
            Path includeInBala = updateModuleDirectoryToMatchNamingInBala(includePath);
            try {
                if (includePathInPackage.toFile().isDirectory()) {
                    putDirectoryToZipFile(includePathInPackage, includeInBala, balaOutputStream);
                } else {
                    putZipEntry(balaOutputStream, includeInBala,
                            new FileInputStream(String.valueOf(includePathInPackage)));
                }
            } catch (ZipException e) {
                if (!e.getMessage().contains("duplicate entry")) {
                    throw e;
                }
            }
        }
    }

    private void addDependenciesJson(ZipOutputStream balaOutputStream) {
        PackageCache packageCache = this.packageContext.project().projectEnvironmentContext()
                .getService(PackageCache.class);
        List<Dependency> packageDependencyGraph = getPackageDependencies(
                this.packageContext.getResolution().dependencyGraph());
        List<ModuleDependency> moduleDependencyGraph = getModuleDependencies(
                this.packageContext.project().currentPackage(), packageCache);

        DependencyGraphJson depGraphJson = new DependencyGraphJson(packageDependencyGraph, moduleDependencyGraph);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        try {
            putZipEntry(balaOutputStream, Path.of(DEPENDENCY_GRAPH_JSON),
                        new ByteArrayInputStream(gson.toJson(depGraphJson).getBytes(Charset.defaultCharset())));
        } catch (IOException e) {
            throw new ProjectException("Failed to write '" + DEPENDENCY_GRAPH_JSON + "' file: " + e.getMessage(), e);
        }
    }

    private Path updateModuleDirectoryToMatchNamingInBala(Path relativePath) {
        // a project with non-default module dir modules/<submodule_name> when packed into a BALA has the structure
        // modules/<package_name>.<submodule_name>
        Path moduleRootPath = Path.of(MODULES_ROOT);
        if (relativePath.startsWith(moduleRootPath)) {
            String packageName = this.packageContext.packageName().toString();
            Path modulePath = moduleRootPath.resolve(moduleRootPath.relativize(relativePath).subpath(0, 1));
            Path pathInsideModule = modulePath.relativize(relativePath);
            String moduleName = Optional.ofNullable(modulePath.getFileName()).orElse(Path.of("")).toString();
            String updatedModuleName = packageName + ProjectConstants.DOT + moduleName;
            Path updatedModulePath = moduleRootPath.resolve(updatedModuleName);
            return updatedModulePath.resolve(pathInsideModule);
        }
        return relativePath;
    }

    private List<Dependency> getPackageDependencies(DependencyGraph<ResolvedPackageDependency> dependencyGraph) {
        List<Dependency> dependencies = new ArrayList<>();
        for (ResolvedPackageDependency resolvedDep : dependencyGraph.getNodes()) {
            if (resolvedDep.scope() == PackageDependencyScope.TEST_ONLY) {
                // We don't add the test dependencies to the bala file.
                continue;
            }

            PackageContext packageContext = resolvedDep.packageInstance().packageContext();
            Dependency dependency = new Dependency(packageContext.packageOrg().toString(),
                    packageContext.packageName().toString(), packageContext.packageVersion().toString());

            List<Dependency> dependencyList = new ArrayList<>();
            Collection<ResolvedPackageDependency> pkgDependencies = dependencyGraph.getDirectDependencies(resolvedDep);
            for (ResolvedPackageDependency resolvedTransitiveDep : pkgDependencies) {
                if (resolvedTransitiveDep.scope() == PackageDependencyScope.TEST_ONLY) {
                    // We don't add the test dependencies to the bala file.
                    continue;
                }
                PackageContext dependencyPkgContext = resolvedTransitiveDep.packageInstance().packageContext();
                Dependency dep = new Dependency(dependencyPkgContext.packageOrg().toString(),
                        dependencyPkgContext.packageName().toString(),
                        dependencyPkgContext.packageVersion().toString());
                dependencyList.add(dep);
            }
            dependency.setDependencies(dependencyList);
            dependencies.add(dependency);
        }
        return dependencies;
    }

    private List<ModuleDependency> getModuleDependencies(Package pkg, PackageCache packageCache) {
        List<ModuleDependency> modules = new ArrayList<>();
        for (ModuleId moduleId : pkg.moduleIds()) {
            Module module = pkg.module(moduleId);
            List<ModuleDependency> moduleDependencies = new ArrayList<>();
            for (io.ballerina.projects.ModuleDependency moduleDependency : module.moduleDependencies()) {
                if (moduleDependency.packageDependency().scope() == PackageDependencyScope.TEST_ONLY) {
                    // Do not test_only scope dependencies
                    continue;
                }
                Package pkgDependency = packageCache.getPackageOrThrow(
                        moduleDependency.packageDependency().packageId());
                Module moduleInPkgDependency = pkgDependency.module(moduleDependency.descriptor().name());
                moduleDependencies.add(createModuleDependencyEntry(pkgDependency, moduleInPkgDependency,
                        Collections.emptyList()));
            }
            modules.add(createModuleDependencyEntry(pkg, module, moduleDependencies));
        }
        return modules;
    }

    private ModuleDependency createModuleDependencyEntry(Package pkg,
                                                         Module module,
                                                         List<ModuleDependency> moduleDependencies) {
        return new ModuleDependency(pkg.packageOrg().value(), pkg.packageName().value(),
                pkg.packageVersion().toString(), module.moduleName().toString(), moduleDependencies);
    }

    private Path getIconPath(String icon) {
        Path iconPath = Path.of(icon);
        if (!iconPath.isAbsolute()) {
            iconPath = this.packageContext.project().sourceRoot().resolve(iconPath);
        }
        return iconPath;
    }

    protected void putZipEntry(ZipOutputStream balaOutputStream, Path fileName, InputStream in)
            throws IOException {
        ZipEntry entry = new ZipEntry(convertPathSeperator(fileName));
        balaOutputStream.putNextEntry(entry);

        IOUtils.copy(in, balaOutputStream);
        IOUtils.closeQuietly(in);
    }

    protected void putDirectoryToZipFile(Path sourceDir, Path pathInZipFile, ZipOutputStream out)
            throws IOException {
        if (sourceDir.toFile().exists()) {
            File[] files = new File(sourceDir.toString()).listFiles();

            if (files != null && files.length > 0) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        putDirectoryToZipFile(sourceDir.resolve(file.getName()), pathInZipFile, out);
                    } else {
                        Path fileNameInBala =
                                pathInZipFile.resolve(sourceDir.relativize(Path.of(file.getPath())));
                        putZipEntry(out, fileNameInBala,
                                new FileInputStream(sourceDir + File.separator + file.getName()));
                    }
                }
            }
        }
    }

    protected abstract Optional<JsonArray> addPlatformLibs(ZipOutputStream balaOutputStream)
            throws IOException;

    protected abstract void addCompilerPlugin(ZipOutputStream balaOutputStream) throws IOException;

    protected abstract void addBalTool(ZipOutputStream balaOutputStream) throws IOException;

    // Following function was put in to handle a bug in windows zipFileSystem
    // Refer https://bugs.openjdk.java.net/browse/JDK-8195141
    private String convertPathSeperator(Path file) {
        if (file == null) {
            return null;
        } else {
            if (File.separatorChar == '\\') {
                String replaced;
                // Following is to evade spotbug issue if file is null
                replaced = Optional.ofNullable(file.getFileName()).orElse(Path.of("")).toString();
                Path parent = file.getParent();
                while (parent != null) {
                    replaced = parent.getFileName() + UNIX_FILE_SEPARATOR + replaced;
                    parent = parent.getParent();
                }
                return replaced;
            }
            return file.toString();
        }
    }
}
