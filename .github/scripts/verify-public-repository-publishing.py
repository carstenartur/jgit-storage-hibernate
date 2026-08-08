#!/usr/bin/env python3
"""Static checks for the anonymous branch-backed Maven repository contract."""
from __future__ import annotations
import sys, xml.etree.ElementTree as ET
from pathlib import Path
NS={"m":"http://maven.apache.org/POM/4.0.0"}
ROOT=Path("pom.xml")
RELEASE=Path(".github/workflows/release.yml")
MAVEN=Path(".github/workflows/maven.yml")
SNAPSHOT=Path(".github/workflows/publish-snapshot.yml")
SCRIPT=Path(".github/scripts/release.sh")
CONSUMER=Path(".github/public-repository-consumer/pom.xml")
GROUP_ID="io.github.carstenartur"
BOM="jgit-storage-hibernate-bom"
PRODUCTION={
    "jgit-storage-hibernate-core",
    "jgit-storage-hibernate-search",
    "jgit-storage-hibernate-java-analysis",
    "jgit-storage-hibernate-architecture",
}
PUBLISHED=PRODUCTION | {BOM, "jgit-storage-hibernate-benchmarks"}

def txt(e,p): return (e.findtext(p,default="",namespaces=NS) if e is not None else "").strip()
def profile(root,pid):
    return next((p for p in root.findall("m:profiles/m:profile",NS) if txt(p,"m:id")==pid),None)
def read(path,errors):
    if not path.is_file(): errors.append(f"missing {path}"); return ""
    return path.read_text(encoding="utf-8")
def main():
    errors=[]
    root=ET.parse(ROOT).getroot()
    if root.find("m:distributionManagement",NS) is not None: errors.append("publication must be profile-gated")
    props=root.find("m:properties",NS)
    if txt(props,"m:maven-deploy-plugin.version")!="3.1.4": errors.append("maven-deploy-plugin must be pinned to 3.1.4")
    public=profile(root,"public-repository-release")
    if public is None: errors.append("missing public-repository-release profile")
    else:
        repo=public.find("m:distributionManagement/m:repository",NS)
        if txt(repo,"m:id")!="public-repository": errors.append("public repository id must be public-repository")
        if txt(repo,"m:url")!="file://${public.repository.directory}": errors.append("public release must deploy to a file staging repository")
    github=profile(root,"github-packages")
    if github is None: errors.append("missing explicit github-packages snapshot profile")
    all_pom=ROOT.read_text(encoding="utf-8")
    for forbidden in ("central-publishing-maven-plugin","maven-gpg-plugin","central-release"):
        if forbidden in all_pom: errors.append(f"obsolete Central publishing fragment remains: {forbidden}")

    reactor={txt(module,".") for module in root.findall("m:modules/m:module",NS)}
    if not PUBLISHED.issubset(reactor):
        errors.append(f"published reactor artifacts missing: {sorted(PUBLISHED-reactor)}")
    for path in sorted(Path('.').glob('jgit-storage-hibernate-*/pom.xml')):
        module=ET.parse(path).getroot()
        packaging=txt(module,"m:packaging") or "jar"
        if packaging=="pom":
            continue
        plugins=module.findall("m:build/m:plugins/m:plugin",NS)
        ids={txt(p,"m:artifactId") for p in plugins}
        if "maven-source-plugin" not in ids or "maven-javadoc-plugin" not in ids:
            errors.append(f"{path} must attach source and Javadoc JARs")

    release=read(RELEASE,errors)
    for fragment in ("release-request/**","ref: main","verify-public-repository-consumption.sh","PUBLIC_REPOSITORY_URL"):
        if fragment not in release: errors.append(f"release workflow missing {fragment}")
    for forbidden in ("MAVEN_CENTRAL_","CENTRAL_USERNAME","MAVEN_GPG"):
        if forbidden in release: errors.append(f"release workflow retains {forbidden}")
    maven=read(MAVEN,errors)
    for fragment in ("Public Maven repository contract","DRY_RUN=true","target/public-repository-evidence/manifest.json"):
        if fragment not in maven: errors.append(f"Maven workflow missing {fragment}")
    if "-Pgithub-packages" not in read(SNAPSHOT,errors): errors.append("snapshot workflow must activate github-packages")
    script=read(SCRIPT,errors)
    for fragment in ("maven-repository","raw.githubusercontent.com","-Ppublic-repository-release","prepare-public-repository.py","verify-public-repository-consumption.sh"):
        if fragment not in script: errors.append(f"release script missing {fragment}")
    for forbidden in ("central-release","CENTRAL_USERNAME","MAVEN_GPG_KEY"):
        if forbidden in script: errors.append(f"release script retains {forbidden}")

    consumer=ET.parse(CONSUMER).getroot()
    url=txt(consumer,"m:properties/m:public.repository.url")
    if "raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/maven-repository" not in url:
        errors.append("consumer default repository URL is wrong")
    artifacts={txt(d,"m:artifactId") for d in consumer.findall("m:dependencies/m:dependency",NS)}
    if artifacts!=PRODUCTION:
        errors.append(f"consumer production artifacts mismatch: {sorted(artifacts)}")
    imports=[
        dependency
        for dependency in consumer.findall("m:dependencyManagement/m:dependencies/m:dependency",NS)
        if txt(dependency,"m:groupId")==GROUP_ID and txt(dependency,"m:artifactId")==BOM
    ]
    if len(imports)!=1:
        errors.append("consumer must import the BOM exactly once")
    else:
        imported=imports[0]
        if txt(imported,"m:version")!="${public.version}":
            errors.append("consumer BOM import must use ${public.version}")
        if txt(imported,"m:type")!="pom" or txt(imported,"m:scope")!="import":
            errors.append("consumer BOM import must use type pom and scope import")
    if any(txt(d,"m:version") for d in consumer.findall("m:dependencies/m:dependency",NS)):
        errors.append("consumer production dependencies must obtain versions from the BOM")
    if Path('.github/workflows/temp-public-repo-source-export.yml').exists():
        errors.append("temporary source export workflow must be removed")
    if errors:
        for error in errors: print(f"ERROR: {error}",file=sys.stderr)
        raise SystemExit(1)
    print("Anonymous public Maven repository publishing contract verified")
if __name__=='__main__': main()
