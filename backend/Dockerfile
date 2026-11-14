# 운영 이미지(T-29). jar는 로컬에서 ./gradlew bootJar로 만든 것을 그대로 쓴다(서버에서 Gradle을 돌리지 않는다).
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/block-battle-backend-0.0.1-SNAPSHOT.jar app.jar
ENV JAVA_OPTS="-Xms256m -Xmx768m -XX:+UseG1GC"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
