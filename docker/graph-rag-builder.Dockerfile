# graph-rag-builder 이미지. 런타임에 Docker 데몬(Testcontainers)과 SUT 입력이 필요하다.
#   docker run --network host -v /var/run/docker.sock:/var/run/docker.sock \
#     -v <sut>:/sut -v <out>:/out ghcr.io/<owner>/graph-rag-builder:<v> build ...
# --network host는 Linux에서만 정상(SUT 프로세스 ↔ Testcontainers DB ↔ JaCoCo TCP가 localhost 공유).
# 빌드 전 `./gradlew :graph-rag-builder:installDist` 선행 필요(빌드 컨텍스트 = repo 루트).
# docker-java가 소켓을 직접 쓰므로 docker CLI는 불필요. JRE면 충분(SUT는 boot jar 실행).
FROM eclipse-temurin:17-jre
COPY graph-rag-builder/build/install/graph-rag-builder /app
ENTRYPOINT ["/app/bin/graph-rag-builder"]
