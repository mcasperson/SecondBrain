FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /usr/src/app

ADD pom.xml ./pom.xml
ADD core/pom.xml ./core/pom.xml
ADD cli/pom.xml ./cli/pom.xml
ADD service/pom.xml ./service/pom.xml
ADD tools/pom.xml ./tools/pom.xml
ADD web/pom.xml ./web/pom.xml

RUN mvn -pl core verify --fail-never --batch-mode
RUN mvn -pl tools verify --fail-never --batch-mode
RUN mvn -pl service verify --fail-never --batch-mode
RUN mvn -pl cli verify --fail-never --batch-mode
RUN mvn -pl web verify --fail-never --batch-mode

COPY .mvn .mvn
COPY cli ./cli
COPY core ./core
COPY service ./service
COPY tools ./tools
COPY web ./web
RUN mvn clean install -DskipTests --batch-mode
RUN cd web && mvn clean package -DskipTests --batch-mode

FROM ollama/ollama:latest

# GitHub overrides the HOME environment variable, which is where the models
# are stored by default. We override the default location to a directory
# that we control.
RUN mkdir -p /ollama/models
ENV OLLAMA_MODELS /ollama/models
# See https://github.com/ollama/ollama/issues/1736
ENV OLLAMA_EXPERIMENT client2

RUN DEBIAN_FRONTEND=noninteractive \
    apt-get update \
    && apt-get install -y curl openjdk-21-jre-headless \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*
RUN curl -#LO https://github.com/atkrad/wait4x/releases/latest/download/wait4x-linux-amd64.tar.gz \
    && tar --one-top-level -xvf wait4x-linux-amd64.tar.gz \
    && cp ./wait4x-linux-amd64/wait4x /usr/local/bin/wait4x \
    && rm wait4x-linux-amd64.tar.gz
RUN nohup bash -c "ollama serve &" \
    && wait4x http http://127.0.0.1:11434 \
    && ollama pull llama3.2:3b \
    && ollama pull qwen3:30b-a3b \
    && ollama list

COPY --from=build /usr/src/app/cli/target/secondbrain-cli-*.jar /usr/local/bin/secondbrain-cli.jar
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]