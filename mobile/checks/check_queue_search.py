"""Run after :app:assembleDebug; uses its cached Kotlin compiler, no test dependencies."""
import os
import argparse
from pathlib import Path
import subprocess
import tomllib
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("--variant", choices=("debug", "release"), default="debug")
variant = parser.parse_args().variant
root = Path(__file__).resolve().parents[1]
cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
version = tomllib.loads((root / "gradle/libs.versions.toml").read_text(encoding="utf-8"))["versions"]["kotlin"]

def jar(group, artifact, version=None):
    folder = cache / group / artifact
    if version:
        folder /= version
    matches = list(folder.rglob("*.jar"))
    if not matches:
        raise SystemExit(f"Missing cached {artifact}; build Android first.")
    return str(max(matches, key=lambda p: p.stat().st_mtime))

compiler = [jar("org.jetbrains.kotlin", name, version) for name in (
    "kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-script-runtime",
    "kotlin-daemon-embeddable", "kotlin-build-tools-api")]
compiler += [jar("org.jetbrains.kotlin", "kotlin-reflect"),
             jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"),
             jar("org.jetbrains", "annotations")]
model = next((p for p in (root / "app/build").rglob("paige/navic/domain/model/DomainSong.class")
              if variant in p.relative_to(root / "app/build").parts), None)
if model is None:
    raise SystemExit("Build Android first; DomainSong.class is missing.")
classes = model.parents[4]
out = root / "build/queue-search-check"
out.mkdir(parents=True, exist_ok=True)
# Resolve the app runtime rather than a plugin's older cached dependencies.
init_script = out / "runtime-classpath.init.gradle"
init_script.write_text('''gradle.beforeProject { app ->
    if (app.path != ':app') return
    app.tasks.register('printCheckRuntimeClasspath') {
        doLast {
            app.configurations.getByName(System.getProperty('navic.check.variant') + 'RuntimeClasspath')
                .incoming.artifactView {
                    componentFilter { it instanceof org.gradle.api.artifacts.component.ModuleComponentIdentifier }
                }.artifacts.artifacts.each { artifact ->
                    def component = artifact.id.componentIdentifier
                    println("NAVIC_CHECK_ARTIFACT\\t${component.group}\\t${component.module}\\t${artifact.file.absolutePath}")
                }
        }
    }
}
''', encoding="utf-8")
resolved = subprocess.run([str(root / ("gradlew.bat" if os.name == "nt" else "gradlew")),
    "-q", "--no-configuration-cache", "-I", str(init_script), f"-Dnavic.check.variant={variant}",
    ":app:printCheckRuntimeClasspath"], cwd=root, text=True, capture_output=True)
if resolved.returncode:
    raise SystemExit(resolved.stdout + resolved.stderr)
runtime = {}
for line in resolved.stdout.splitlines():
    if line.startswith("NAVIC_CHECK_ARTIFACT\t"):
        _, group, artifact, path = line.split("\t", 3)
        runtime[group, artifact] = Path(path)

def runtime_jar(group, artifact):
    source = runtime[group, artifact]
    if source.suffix == ".jar":
        return str(source)
    target = out / (artifact + ".jar")
    with zipfile.ZipFile(source) as archive:
        target.write_bytes(archive.read("classes.jar"))
    return str(target)

classpath = os.pathsep.join([str(classes), runtime_jar("org.jetbrains.kotlin", "kotlin-stdlib"),
    runtime_jar("androidx.room3", "room3-runtime-android"),
    runtime_jar("androidx.sqlite", "sqlite-android"),
    runtime_jar("androidx.paging", "paging-common-android"),
    runtime_jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"),
    runtime_jar("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm")])
source = root / "app/src/main/kotlin/paige/navic/ui/screen/queue/QueueSearch.kt"
subprocess.run(["java", "-cp", os.pathsep.join(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
    "-no-stdlib", "-no-reflect", "-classpath", classpath, "-d", str(out),
    str(source), str(root / "checks/QueueSearchCheck.kt")], check=True)
subprocess.run(["java", "-cp", os.pathsep.join([str(out), classpath]),
    "paige.navic.ui.screen.queue.QueueSearchCheckKt"], check=True)
