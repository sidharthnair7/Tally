# Tally's hosted demo: the Java app (offline mode, the same SQL on H2) plus the Meterless H-MEM memory sidecar.
#   docker build -t tally .
#   docker run -d -p 80:8080 -e TALLY_PER_VISITOR=1 tally
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src src
COPY web web
RUN mvn -q -DskipTests package

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends nodejs npm && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY memory/package.json memory/server.ts memory/
RUN cd memory && npm install --no-audit --no-fund && npm run setup
COPY data/sources data/sources
COPY --from=build /src/target/tally.jar tally.jar
EXPOSE 8080
CMD ["sh", "-c", "cd memory && (npx tsx server.ts &) && cd .. && sleep 2 && java -jar tally.jar"]
