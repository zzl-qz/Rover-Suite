# Release checklist

Before publishing a release:

```bash
mvn -DskipTests package
mvn dependency:tree -DoutputFile=target/dependency-tree.txt
docker compose -f deploy/docker/docker-compose.yml build
docker compose -f deploy/docker/docker-compose.yml up -d
curl http://127.0.0.1:9090/api/overview
curl http://127.0.0.1:8080/api/hello
docker compose -f deploy/docker/docker-compose.yml down
```

For an SBOM, run a CycloneDX Maven plugin in a clean release workspace, for
example:

```bash
mvn org.cyclonedx:cyclonedx-maven-plugin:2.8.1:makeAggregateBom
```

Publish the generated `target/bom.json` or `target/bom.xml` together with the
JARs, SHA256 checksums, source tag, `LICENSE`, and `NOTICE`. Review the BOM for
licenses and vulnerabilities before creating the release.

The release gate is a real-container smoke test rather than a CI/unit-test
requirement. The repository intentionally does not commit generated SBOM
files: they must describe the exact dependency graph of the tagged release.

Before publishing, also verify that the bilingual README, Admin/API,
configuration, deployment, troubleshooting, plugin, and compatibility guides
are synchronized. Confirm that Admin screenshots contain only local demo data,
and replace the Docker demo's tokens, image tags, port mappings, and ACL policy
for any non-development environment. Add real benchmark numbers (machine,
JDK, CPU, memory, Docker and load-tool versions, plus P50/P95/P99) when the
release performance report is ready.
