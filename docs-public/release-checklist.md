# Release checklist

Before publishing a release:

```bash
./deploy/scripts/backup-overlays.sh
# or: ./deploy/scripts/backup-overlays.sh deploy/docker/config

mvn -DskipTests package
mvn dependency:tree -DoutputFile=target/dependency-tree.txt

# One-shot smoke: build → up → health/overview/business path → down
./deploy/scripts/smoke-compose.sh
```

Manual equivalent:

```bash
docker compose -f deploy/docker/docker-compose.yml build
docker compose -f deploy/docker/docker-compose.yml up -d
curl -fsS -H "X-Rover-Admin-Token: rover-compose-gateway-admin-token" \
  http://127.0.0.1:8889/_manage/health
curl -fsS -H "X-Rover-Admin-Token: rover-compose-gateway-admin-token" \
  http://127.0.0.1:8080/_manage/health
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
licenses and vulnerabilities before creating the release. Admin Vue/ECharts are
vendored under `rover-admin/.../static/vendor/` (no public CDN at runtime).

The release gate is a real-container smoke test rather than a CI/unit-test
requirement. The repository intentionally does not commit generated SBOM
files: they must describe the exact dependency graph of the tagged release.

Before publishing, also verify that the bilingual README, Admin/API,
configuration, deployment, troubleshooting, plugin, and compatibility guides
are synchronized. Confirm that Admin screenshots contain only local demo data,
and use [`deploy/production/`](../deploy/production/) for non-demo hosts. Fill
real benchmark numbers in the performance report when ready.
