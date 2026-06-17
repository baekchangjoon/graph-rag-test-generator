# test-generator 이미지. Docker도 DB도 불필요한 가벼운 도구(graph.json → 테스트 .java).
# 빌드 전 `./gradlew :test-generator:installDist` 선행 필요(빌드 컨텍스트 = repo 루트).
FROM eclipse-temurin:17-jre
COPY test-generator/build/install/test-generator /app
ENTRYPOINT ["/app/bin/test-generator"]
