# 개발용 Jenkins: JDK 21(Gradle 빌드) + Docker CLI·Compose(이미지 빌드, Testcontainers, 개발 환경 배포) + 필요한 Plugin
FROM jenkins/jenkins:2.516.3-lts-jdk21

USER root
# Docker 공식 apt 저장소에서 CLI와 Compose Plugin만 설치한다 (Docker Engine은 호스트 것을 소켓으로 사용).
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl gnupg \
    && install -m 0755 -d /etc/apt/keyrings \
    && curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
       > /etc/apt/sources.list.d/docker.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends docker-ce-cli docker-compose-plugin \
    && rm -rf /var/lib/apt/lists/*
USER jenkins

# Plugin은 이미지에 미리 설치한다 (재현 가능한 CI 환경). 설정은 JCasC(casc.yaml)로 코드화한다.
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt
COPY casc.yaml /usr/share/jenkins/casc.yaml
