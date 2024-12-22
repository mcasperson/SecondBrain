FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /usr/src/app
COPY pom.xml .
COPY .mvn .mvn
COPY cli ./cli
COPY core ./core
COPY service ./service
COPY tools ./tools
COPY web ./web
RUN mvn clean install -DskipTests --batch-mode
RUN cd web && mvn clean package -DskipTests --batch-mode

FROM ollama/ollama:latest
RUN DEBIAN_FRONTEND=noninteractive \
    apt-get update \
    && apt-get install -y curl openjdk-21-jre-headless \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /usr/src/app/cli/target/secondbrain-cli-*.jar /usr/local/bin/secondbrain-cli.jar
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN curl -#LO https://github.com/atkrad/wait4x/releases/latest/download/wait4x-linux-amd64.tar.gz \
    && tar --one-top-level -xvf wait4x-linux-amd64.tar.gz \
    && cp ./wait4x-linux-amd64/wait4x /usr/local/bin/wait4x \
    && rm wait4x-linux-amd64.tar.gz
RUN nohup bash -c "ollama serve &" \
    && wait4x http http://127.0.0.1:11434 \
    && ollama run qwen2:1.5b \
    && ollama run llama3.2:3b

RUN chmod +x /usr/local/bin/entrypoint.sh

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]