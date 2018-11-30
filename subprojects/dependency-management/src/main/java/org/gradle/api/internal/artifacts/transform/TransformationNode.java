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

package org.gradle.api.internal.artifacts.transform;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.Iterables;
import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.artifacts.ResolvableDependencies;
import org.gradle.api.artifacts.ResolveException;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.internal.artifacts.ivyservice.DefaultLenientConfiguration;
import org.gradle.api.internal.artifacts.ivyservice.ResolvedArtifactCollectingVisitor;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.BuildableSingleResolvedArtifactSet;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvableArtifact;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedArtifactSet;
import org.gradle.execution.plan.Node;
import org.gradle.execution.plan.TaskDependencyResolver;
import org.gradle.internal.operations.BuildOperationCategory;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.BuildOperationQueue;
import org.gradle.internal.operations.RunnableBuildOperation;

import java.io.File;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class TransformationNode extends Node {
    private static final AtomicInteger ORDER_COUNTER = new AtomicInteger();

    private final int order = ORDER_COUNTER.incrementAndGet();
    protected final TransformationStep transformationStep;
    protected TransformationSubject transformedSubject;

    public static TransformationNode chained(TransformationStep current, TransformationNode previous) {
        return new ChainedTransformationNode(current, previous);
    }

    public static TransformationNode initial(TransformationStep initial, BuildableSingleResolvedArtifactSet artifact, ResolvableDependencies resolvableDependencies) {
        return new InitialTransformationNode(initial, artifact, resolvableDependencies);
    }

    protected TransformationNode(TransformationStep transformationStep) {
        this.transformationStep = transformationStep;
    }

    public abstract void execute(BuildOperationExecutor buildOperationExecutor, ArtifactTransformListener transformListener);

    protected abstract ArtifactTransformDependenciesProvider getDependenciesProvider();

    @Override
    public String toString() {
        return transformationStep.getDisplayName();
    }

    private TransformationSubject getTransformedSubject() {
        if (transformedSubject == null) {
            throw new IllegalStateException("Transformation hasn't been executed yet");
        }
        return transformedSubject;
    }

    @Override
    public Set<Node> getFinalizers() {
        return Collections.emptySet();
    }


    @Override
    public void prepareForExecution() {
    }

    @Override
    public void collectTaskInto(ImmutableCollection.Builder<Task> builder) {
    }

    @Override
    public Throwable getNodeFailure() {
        return null;
    }

    @Override
    public void rethrowNodeFailure() {
    }

    @Override
    public int compareTo(Node other) {
        if (getClass() != other.getClass()) {
            return getClass().getName().compareTo(other.getClass().getName());
        }
        TransformationNode otherTransformation = (TransformationNode) other;
        return order - otherTransformation.order;
    }

    private static class InitialTransformationNode extends TransformationNode {
        private final BuildableSingleResolvedArtifactSet artifactSet;
        private final ResolvableDependencies resolvableDependencies;
        private ArtifactTransformDependenciesProvider dependenciesProvider;

        public InitialTransformationNode(
            TransformationStep transformationStep,
            BuildableSingleResolvedArtifactSet artifactSet,
            ResolvableDependencies resolvableDependencies) {
            super(transformationStep);
            this.artifactSet = artifactSet;
            this.resolvableDependencies = resolvableDependencies;
        }

        @Override
        public void execute(BuildOperationExecutor buildOperationExecutor, ArtifactTransformListener transformListener) {
            InitialArtifactTransformationStepOperation transformationStep = new InitialArtifactTransformationStepOperation(buildOperationExecutor);
            buildOperationExecutor.run(transformationStep);
            this.transformedSubject = transformationStep.getTransformedSubject();
        }

        @Override
        public void resolveDependencies(TaskDependencyResolver dependencyResolver, Action<Node> processHardSuccessor) {
            Set<Node> dependencies = getDependencies(dependencyResolver);
            for (Node dependency : dependencies) {
                addDependencySuccessor(dependency);
                processHardSuccessor.execute(dependency);
            }
        }

        @Override
        protected ArtifactTransformDependenciesProvider getDependenciesProvider() {
            if (dependenciesProvider == null) {
                throw new IllegalStateException("Initial transformation hasn't been executed yet");
            }
            return dependenciesProvider;
        }


        private Set<Node> getDependencies(TaskDependencyResolver dependencyResolver) {
            return dependencyResolver.resolveDependenciesFor(null, artifactSet);
        }

        private class InitialArtifactTransformationStepOperation extends ArtifactTransformationStepBuildOperation {
            private final BuildOperationExecutor buildOperationExecutor;

            public InitialArtifactTransformationStepOperation(BuildOperationExecutor buildOperationExecutor) {
                this.buildOperationExecutor = buildOperationExecutor;
            }

            @Override
            protected String describeSubject() {
                return "artifact " + artifactSet.getArtifactId().getDisplayName();
            }

            @Override
            protected TransformationSubject transform() {
                ResolveArtifacts resolveArtifacts = new ResolveArtifacts(artifactSet);
                buildOperationExecutor.runAll(resolveArtifacts);
                ResolvedArtifactCollectingVisitor visitor = new ResolvedArtifactCollectingVisitor();
                resolveArtifacts.getResult().visit(visitor);
                Set<Throwable> failures = visitor.getFailures();
                if (!failures.isEmpty()) {
                    Throwable failure;
                    if (failures.size() == 1 && Iterables.getOnlyElement(failures) instanceof ResolveException) {
                        failure = Iterables.getOnlyElement(failures);
                    } else {
                        failure = new DefaultLenientConfiguration.ArtifactResolveException("artifacts", transformationStep.getDisplayName(), "artifact transform", failures);
                    }
                    return TransformationSubject.failure("artifact " + artifactSet.getArtifactId().getDisplayName(), failure);
                }
                ResolvedArtifactResult artifact = Iterables.getOnlyElement(visitor.getArtifacts());
                dependenciesProvider = DefaultArtifactTransformDependenciesProvider.create(transformationStep, artifact.getId(), resolvableDependencies);
                TransformationSubject initialArtifactTransformationSubject = TransformationSubject.initial(artifact.getId(), artifact.getFile());

                return transformationStep.transform(initialArtifactTransformationSubject, dependenciesProvider);
            }
        }
    }

    private static class ChainedTransformationNode extends TransformationNode {
        private final TransformationNode previousTransformationNode;

        public ChainedTransformationNode(TransformationStep transformationStep, TransformationNode previousTransformationNode) {
            super(transformationStep);
            this.previousTransformationNode = previousTransformationNode;
        }

        @Override
        public void execute(BuildOperationExecutor buildOperationExecutor, ArtifactTransformListener transformListener) {
            ChainedArtifactTransformStepOperation chainedArtifactTransformStep = new ChainedArtifactTransformStepOperation();
            buildOperationExecutor.run(chainedArtifactTransformStep);
            this.transformedSubject = chainedArtifactTransformStep.getTransformedSubject();
        }

        @Override
        protected ArtifactTransformDependenciesProvider getDependenciesProvider() {
            return previousTransformationNode.getDependenciesProvider();
        }

        @Override
        public void resolveDependencies(TaskDependencyResolver dependencyResolver, Action<Node> processHardSuccessor) {
            addDependencySuccessor(previousTransformationNode);
            processHardSuccessor.execute(previousTransformationNode);
        }

        private class ChainedArtifactTransformStepOperation extends ArtifactTransformationStepBuildOperation {
            @Override
            protected String describeSubject() {
                return previousTransformationNode.getTransformedSubject().getDisplayName();
            }

            @Override
            protected TransformationSubject transform() {
                TransformationSubject transformedSubject = previousTransformationNode.getTransformedSubject();
                if (transformedSubject.getFailure() != null) {
                    return transformedSubject;
                }
                return transformationStep.transform(transformedSubject, getDependenciesProvider());
            }
        }
    }

    private static class ResolveArtifacts implements Action<BuildOperationQueue<RunnableBuildOperation>> {
        private final ResolvedArtifactSet artifactSet;
        private ResolvedArtifactSet.Completion result;

        public ResolveArtifacts(ResolvedArtifactSet artifactSet) {
            this.artifactSet = artifactSet;
        }

        @Override
        public void execute(BuildOperationQueue<RunnableBuildOperation> actions) {
            result = artifactSet.startVisit(actions, new ResolveOnlyAsyncArtifactListener());
        }

        public ResolvedArtifactSet.Completion getResult() {
            return result;
        }
    }

    private static class ResolveOnlyAsyncArtifactListener implements ResolvedArtifactSet.AsyncArtifactListener {
        @Override
        public void artifactAvailable(ResolvableArtifact artifact) {
        }

        @Override
        public boolean requireArtifactFiles() {
            return true;
        }

        @Override
        public boolean includeFileDependencies() {
            return false;
        }

        @Override
        public void fileAvailable(File file) {
        }
    }

    abstract class ArtifactTransformationStepBuildOperation implements RunnableBuildOperation {

        private TransformationSubject transformedSubject;

        @Override
        public final BuildOperationDescriptor.Builder description() {
            String displayName = transformationStep.getDisplayName();
            String subjectName = describeSubject();
            String basicName = subjectName + " with " + displayName;
            return BuildOperationDescriptor.displayName("Transform " + basicName)
                .progressDisplayName("Transforming " + basicName)
                .operationType(BuildOperationCategory.TRANSFORM)
                .details(new OperationDetails(displayName, subjectName));
        }

        protected abstract String describeSubject();

        @Override
        public final void run(BuildOperationContext context) {
            transformedSubject = transform();
            context.setResult(ExecuteScheduledTransformBuildOperationType.RESULT);
            context.failed(transformedSubject.getFailure());
        }

        protected abstract TransformationSubject transform();

        public TransformationSubject getTransformedSubject() {
            return transformedSubject;
        }
    }

    private static class OperationDetails implements ExecuteScheduledTransformBuildOperationType.Details {

        private final String displayName;
        private final String subjectName;

        public OperationDetails(String displayName, String subjectName) {
            this.displayName = displayName;
            this.subjectName = subjectName;
        }

        @Override
        public String getDisplayName() {
            return displayName;
        }

        @Override
        public String getSubjectName() {
            return subjectName;
        }
    }
}
