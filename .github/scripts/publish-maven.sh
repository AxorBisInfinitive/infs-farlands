#!/usr/bin/env bash
set -euo pipefail

release_tag=$RELEASE_TAG
if [[ ! "$release_tag" =~ ^(fabric-26\.1\.2-)?v([0-9]+\.[0-9]+\.[0-9]+(-alpha([.][0-9]+)?)?)$ ]]; then
    echo "Unsupported Farlands release tag: $release_tag" >&2
    exit 1
fi

init_script="$RUNNER_TEMP/farlands-pages-maven.init.gradle"
cp .github/scripts/pages-maven.init.gradle "$init_script"
gh auth setup-git
git fetch --depth=1 origin "refs/tags/$release_tag"
git checkout --detach FETCH_HEAD
version=$(sed -n 's/^version=//p' gradle.properties | tr -d '\r')
tag_version=${release_tag##*v}
if [[ "$version" != "$tag_version" && "$version" != "$tag_version-alpha" ]]; then
    echo "Tag $release_tag and Gradle version $version do not match" >&2
    exit 1
fi

release=$(gh api "repos/$GITHUB_REPOSITORY/releases/tags/$release_tag")
jar="infs-farlands-$version.jar"
jq -e --arg jar "$jar" '.draft == false and ([.assets[] | select(.name == $jar)] | length == 1)' <<< "$release" > /dev/null
mkdir -p build/libs
gh release download "$release_tag" --repo "$GITHUB_REPOSITORY" --pattern "$jar" --dir build/libs
unzip -p "build/libs/$jar" fabric.mod.json | jq -e --arg version "$version" \
    '.id == "infs-farlands" and .version == $version' > /dev/null

history=build/pages-maven-history
refs=$(git ls-remote --heads origin refs/heads/maven-repository)
if [[ -n "$refs" ]]; then
    git clone --depth=1 --branch maven-repository "${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}.git" "$history"
else
    git init --initial-branch=maven-repository "$history"
    git -C "$history" remote add origin "${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}.git"
fi

published="$history/maven/com/inf/farlands/infs-farlands/$version"
if [[ -d "$published" ]]; then
    cmp "build/libs/$jar" "$published/$jar" || {
        echo "Refusing to overwrite published version $version" >&2
        exit 1
    }
else
    # Publish the Release JAR byte for byte. Gradle generates metadata from that file.
    bash gradlew --no-daemon --console=plain --init-script "$init_script" \
        "-PmodVersion=$version" "-PpagesMavenDir=$history/maven" \
        -x jar publishMavenJavaPublicationToPagesRepository
    cmp "build/libs/$jar" "$published/$jar"
    git -C "$history" add maven
    git -C "$history" -c user.name='github-actions[bot]' \
        -c user.email='41898282+github-actions[bot]@users.noreply.github.com' \
        commit -m "Publish Farlands $version to Maven"
    git -C "$history" push origin HEAD:refs/heads/maven-repository
fi

# repository_dispatch runs on the default branch allowed by the Pages environment.
gh api --method POST "repos/$GITHUB_REPOSITORY/dispatches" -f event_type=farlands-maven-published
