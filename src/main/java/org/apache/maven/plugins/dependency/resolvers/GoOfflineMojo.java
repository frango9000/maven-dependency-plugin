/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.dependency.resolvers;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.plugins.dependency.filters.ArtifactIdFilter;
import org.apache.maven.plugins.dependency.filters.ClassifierFilter;
import org.apache.maven.plugins.dependency.filters.FilterDependencies;
import org.apache.maven.plugins.dependency.filters.GroupIdFilter;
import org.apache.maven.plugins.dependency.filters.ScopeFilter;
import org.apache.maven.plugins.dependency.filters.TypeFilter;
import org.apache.maven.plugins.dependency.utils.DependencyUtil;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.artifact.filter.collection.ArtifactsFilter;
import org.apache.maven.shared.artifact.filter.resolve.TransformableFilter;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResult;
import org.apache.maven.shared.transfer.dependencies.DefaultDependableCoordinate;
import org.apache.maven.shared.transfer.dependencies.DependableCoordinate;
import org.apache.maven.shared.transfer.dependencies.resolve.DependencyResolverException;

import static java.util.Collections.unmodifiableSet;

/**
 * Goal that resolves all project dependencies, including plugins and reports and their dependencies.
 *
 * @author <a href="mailto:brianf@apache.org">Brian Fox</a>
 * @author Maarten Mulders
 * @since 2.0
 */
@Mojo(name = "go-offline", requiresDependencyCollection = ResolutionScope.TEST, threadSafe = true)
@Execute(goal = "resolve-plugins")
public class GoOfflineMojo extends AbstractResolveMojo {
    /**
     * Include parent poms in the dependency resolution list.
     *
     * @since 3.1.2
     */
    @Parameter(property = "includeParents", defaultValue = "false")
    private boolean includeParents;

    private Set<Artifact> dependencies;

    /**
     * Main entry into mojo. Gets the list of dependencies, filters them by the include/exclude parameters
     * provided and iterates through downloading the resolved version.
     * if the version is not present in the local repository.
     *
     * @throws MojoExecutionException with a message if an error occurs.
     */
    @Override
    protected void doExecute() throws MojoExecutionException {

        try {
            final Set<Artifact> plugins = resolvePluginArtifacts();

            this.dependencies = resolveDependencyArtifacts();

            if (!isSilent()) {
                for (Artifact artifact : plugins) {
                    this.getLog().info("Resolved plugin: " + DependencyUtil.getFormattedFileName(artifact, false));
                }

                for (Artifact artifact : dependencies) {
                    this.getLog().info("Resolved dependency: " + DependencyUtil.getFormattedFileName(artifact, false));
                }
            }

        } catch (DependencyResolverException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    /**
     * This method resolves the dependency artifacts from the project.
     *
     * @return set of resolved dependency artifacts.
     * @throws DependencyResolverException in case of an error while resolving the artifacts.
     */
    protected Set<Artifact> resolveDependencyArtifacts() throws DependencyResolverException {
        Collection<Dependency> dependencies = getProject().getDependencies();
        final FilterDependencies filterDependencies = new FilterDependencies(
                new ArtifactIdFilter(this.includeArtifactIds, this.excludeArtifactIds),
                new GroupIdFilter(this.includeGroupIds, this.excludeGroupIds),
                new ScopeFilter(this.includeScope, this.excludeScope),
                new ClassifierFilter(this.includeClassifiers, this.excludeClassifiers),
                new TypeFilter(this.includeTypes, this.excludeTypes));
        dependencies = filterDependencies.filter(dependencies);

        Set<DependableCoordinate> dependableCoordinates = dependencies.stream()
                .map(this::createDependendableCoordinateFromDependency)
                .collect(Collectors.toSet());

        ProjectBuildingRequest buildingRequest = newResolveArtifactProjectBuildingRequest();

        return resolveDependableCoordinate(buildingRequest, dependableCoordinates, "dependencies");
    }

    private Set<Artifact> resolveDependableCoordinate(
            final ProjectBuildingRequest buildingRequest,
            final Collection<DependableCoordinate> dependableCoordinates,
            final String type)
            throws DependencyResolverException {
        final TransformableFilter filter = getTransformableFilter();

        this.getLog().debug("Resolving '" + type + "' with following repositories:");
        for (ArtifactRepository repo : buildingRequest.getRemoteRepositories()) {
            getLog().debug(repo.getId() + " (" + repo.getUrl() + ")");
        }

        final Set<Artifact> results = new HashSet<>();

        for (DependableCoordinate dependableCoordinate : dependableCoordinates) {
            try {
                Iterable<ArtifactResult> artifactResults =
                        getDependencyResolver().resolveDependencies(buildingRequest, dependableCoordinate, filter);

                for (final ArtifactResult artifactResult : artifactResults) {
                    results.add(artifactResult.getArtifact());
                }
            } catch (DependencyResolverException e) {
                getLog().warn("Failed to resolve " + type + " for " + dependableCoordinate);
            }
        }

        return results;
    }

    private TransformableFilter getTransformableFilter() {
        if (this.excludeReactor) {
            return new ExcludeReactorProjectsDependencyFilter(this.reactorProjects, getLog());
        } else {
            return null;
        }
    }

    /**
     * This method resolves the plugin artifacts from the project.
     *
     * @return set of resolved plugin artifacts.
     * @throws DependencyResolverException in case of an error while resolving the artifacts.
     */
    protected Set<Artifact> resolvePluginArtifacts() throws DependencyResolverException {

        Set<Artifact> plugins = getProject().getPluginArtifacts();
        Set<Artifact> reports = getProject().getReportArtifacts();

        Set<Artifact> artifacts = new LinkedHashSet<>();
        artifacts.addAll(reports);
        artifacts.addAll(plugins);

        Set<DependableCoordinate> dependableCoordinates = artifacts.stream()
                .map(this::createDependendableCoordinateFromArtifact)
                .collect(Collectors.toSet());

        ProjectBuildingRequest buildingRequest = newResolvePluginProjectBuildingRequest();

        return resolveDependableCoordinate(buildingRequest, dependableCoordinates, "plugins");
    }

    private DependableCoordinate createDependendableCoordinateFromArtifact(final Artifact artifact) {
        final DefaultDependableCoordinate result = new DefaultDependableCoordinate();
        result.setGroupId(artifact.getGroupId());
        result.setArtifactId(artifact.getArtifactId());
        result.setVersion(artifact.getVersion());
        result.setType(artifact.getType());
        result.setClassifier(artifact.getClassifier());

        return result;
    }

    private DependableCoordinate createDependendableCoordinateFromDependency(final Dependency dependency) {
        final DefaultDependableCoordinate result = new DefaultDependableCoordinate();
        result.setGroupId(dependency.getGroupId());
        result.setArtifactId(dependency.getArtifactId());
        result.setVersion(dependency.getVersion());
        result.setType(dependency.getType());
        result.setClassifier(dependency.getClassifier());

        return result;
    }

    @Override
    protected ArtifactsFilter getMarkedArtifactFilter() {
        return null;
    }

    /**
     * Returns a read-only set of dependencies used for going offline.
     *
     * @return an immutable set of dependencies used for going offline.
     */
    protected Set<Artifact> getDependencies() {
        return unmodifiableSet(dependencies);
    }
}
