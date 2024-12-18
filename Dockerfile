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

# Based on https://hub.docker.com/r/payara/micro/dockerfile
# Modified to use a GLIBC based image
FROM azul/zulu-openjdk:21-jre

RUN apt-get update && apt-get install -y wget && rm -rf /var/lib/apt/lists/*

# Configure environment variables
ENV PAYARA_HOME=/opt/payara\
    DEPLOY_DIR=/opt/payara/deployments

# Create and set the Payara user and working directory owned by the new user
RUN addgroup payara && \
    useradd -g payara -d ${PAYARA_HOME} -ms /bin/bash payara && \
    echo payara:payara | chpasswd && \
    mkdir -p ${DEPLOY_DIR} && \
    chown -R payara:payara ${PAYARA_HOME}
USER payara
WORKDIR ${PAYARA_HOME}

# Default command to run
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=90.0", "-jar", "payara-micro.jar"]
CMD ["--deploymentDir", "/opt/payara/deployments"]

# Download specific
ARG PAYARA_VERSION="6.2024.10"
ENV PAYARA_VERSION="$PAYARA_VERSION"
RUN wget --no-verbose -O ${PAYARA_HOME}/payara-micro.jar https://repo1.maven.org/maven2/fish/payara/extras/payara-micro/${PAYARA_VERSION}/payara-micro-${PAYARA_VERSION}.jar

COPY --from=build /usr/src/app/web/target/secondbrain-web-*.war $DEPLOY_DIR
EXPOSE 8080
EXPOSE 8181
EXPOSE 6900
ENV SB_SLACK_CLIENTID=changeme
ENV SB_SLACK_CLIENTSECRET=changeme
ENV SB_ENCRYPTION_PASSWORD=123456789
ENV SB_ZENDESK_ACCESSTOKEN=changeme
ENV SB_GOOGLE_CLIENTID=changeme
ENV SB_GOOGLE_CLIENTSECRET=changeme
ENV SB_GOOGLE_REDIRECTURL=changeme
CMD ["--deploymentDir", "/opt/payara/deployments", "--nocluster", "--port", "8080", "--sslPort", "8181", "--contextroot", "ROOT"]