FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /usr/src/app
COPY pom.xml .
COPY cli ./cli
COPY core ./core
COPY service ./service
COPY tools ./tools
COPY web ./web
RUN mvn clean install -DskipTests
RUN cd web && mvn clean package -DskipTests

FROM payara/micro
COPY --from=build /usr/src/app/web/target/secondbrain-web-*.war $DEPLOY_DIR
EXPOSE 8080
EXPOSE 8181
ENV SB_SLACK_CLIENTID=changeme
ENV SB_SLACK_CLIENTSECRET=changeme
ENV SB_ENCRYPTION_PASSWORD=123456789
ENV SB_ZENDESK_ACCESSTOKEN=changeme
ENV SB_GOOGLE_CLIENTID=changeme
ENV SB_GOOGLE_CLIENTSECRET=changeme
ENV SB_GOOGLE_REDIRECTURL=changeme
CMD ["--deploymentDir", "/opt/payara/deployments", "--port", "8080", "--sslPort", "8181", "--contextroot", "ROOT"]