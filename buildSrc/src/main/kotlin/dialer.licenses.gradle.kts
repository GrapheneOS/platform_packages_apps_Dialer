import dialer.licenses.CopyrightOverride
import dialer.licenses.ExtraNotice
import dialer.licenses.GenerateLicensesTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

tasks.register<GenerateLicensesTask>("generateLicenses") {
    group = "documentation"
    description = "Regenerates the third_party_licenses raw resources from the release classpath."

    targetConfiguration.set(
        project.provider {
            val name = configurationName.get()
            project.configurations.findByName(name)
                ?: throw GradleException("Configuration '$name' not found in ${project.path}")
        },
    )
    val moduleArtifacts = targetConfiguration.map {
        it.incoming.artifactView {
            componentFilter { id -> id is ModuleComponentIdentifier }
        }.artifacts
    }
    artifactFiles.from(moduleArtifacts.map { it.artifactFiles })
    resolvedArtifacts.set(moduleArtifacts.flatMap { it.resolvedArtifacts })
    dependencyHandler.set(project.dependencies)

    val rawDir = rootProject.file("java/com/android/dialer/about/res/raw")
    licensesOutput.set(rawDir.resolve("third_party_licenses"))
    metadataOutput.set(rawDir.resolve("third_party_license_metadata"))

    copyrightOverrides.addAll(
        CopyrightOverride(
            coordinates = "org.codehaus.mojo:animal-sniffer-annotations",
            copyright = "Copyright (c) 2009 codehaus.org.",
        ),
        CopyrightOverride(
            coordinates = "com.github.bumptech.glide:*",
            copyright = "Copyright 2014 Google, Inc. All rights reserved.",
        ),
        CopyrightOverride(
            coordinates = "com.google.protobuf:*",
            copyright = "Copyright 2008 Google Inc.  All rights reserved.",
        ),
    )

    extraNotices.add(
        ExtraNotice(
            name = "Android Open Source Project",
            spdxId = "Apache-2.0",
            text = "Copyright (c) 2005-2008, The Android Open Source Project",
        ),
    )
}
