// Enterprise Integration Hub CI/CD (개발 환경 전용). 실행 환경과 설정: ci/, docs/cicd.md
//   Gitea push → Webhook(notifyCommit) → Checkout → Build → Unit Test → Integration Test → Dependency Scan
//   → Package(Docker) → Nexus(JAR, Image) → 개발 환경 배포 → Healthcheck (실패 시 이전 버전으로 자동 Rollback)
// 어느 단계든 실패하면 이후 단계(배포 포함)는 실행되지 않는다. 운영 환경 배포 단계는 없다.

REGISTRY = 'localhost:8092'                 // Nexus docker-hosted (호스트 Docker가 push/pull)
NEXUS_MAVEN_URL = 'http://nexus:8081/repository/maven-snapshots/'  // Jenkins → Nexus (ci 네트워크)
COMPOSE = 'docker compose -p integration-hub -f docker-compose.yml -f ci/docker-compose.dev.yml'

// 개발 환경에 지정한 이미지 Tag를 배포한다. 비밀값(.env)과 Registry 계정은 Jenkins Credential에서만 주입한다.
def deployDev(String tag, String compose) {
  withCredentials([
      file(credentialsId: 'dev-env-file', variable: 'DEV_ENV'),
      usernamePassword(credentialsId: 'nexus-credentials', usernameVariable: 'NEXUS_USER', passwordVariable: 'NEXUS_PASSWORD')]) {
    withEnv(["IMAGE_TAG=${tag}"]) {
      sh """
        echo "\$NEXUS_PASSWORD" | docker login ${REGISTRY} -u "\$NEXUS_USER" --password-stdin
        ${compose} --env-file "\$DEV_ENV" up -d --no-build --wait --wait-timeout 300
      """
    }
  }
}

pipeline {
  agent any

  options {
    timestamps()
    disableConcurrentBuilds()        // 같은 개발 환경에 두 빌드가 동시에 배포하지 않도록
    timeout(time: 60, unit: 'MINUTES')
    buildDiscarder(logRotator(numToKeepStr: '20'))
  }

  // Job 설정(casc.yaml)과 같게 유지한다. 일정 없이 Webhook(notifyCommit)으로만 변경을 확인한다.
  triggers { pollSCM('') }

  environment {
    REGISTRY = "${REGISTRY}"
    // 빌드마다 독립된 Docker 설정 디렉터리 (registry 로그인 정보가 Jenkins 홈에 남지 않도록 post에서 삭제)
    DOCKER_CONFIG = "${WORKSPACE}@tmp/docker-config"
    // 마지막으로 Healthcheck까지 통과한 이미지 Tag (Rollback 대상)
    DEPLOY_STATE = "${JENKINS_HOME}/deploy/integration-hub-dev.last-success"
  }

  stages {
    stage('Checkout') {
      steps {
        checkout scm
        script {
          env.GIT_SHORT = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
          // 이미지·배포 버전: 빌드 번호 + 커밋 (어떤 소스로 만든 이미지인지 추적 가능)
          env.IMAGE_TAG = "${env.BUILD_NUMBER}-${env.GIT_SHORT}"
        }
        echo "IMAGE_TAG=${env.IMAGE_TAG}"
      }
    }

    stage('Gradle Build') {
      steps {
        sh './gradlew --no-daemon clean assemble'
      }
    }

    stage('Unit Test') {
      steps {
        sh './gradlew --no-daemon unitTest'
      }
      post {
        always { junit allowEmptyResults: true, testResults: '**/build/test-results/unitTest/*.xml' }
      }
    }

    stage('Integration Test') {
      steps {
        // Testcontainers: 호스트 Docker에 PostgreSQL, Redis, WireMock 컨테이너를 띄운다.
        sh './gradlew --no-daemon integrationTest'
      }
      post {
        always { junit '**/build/test-results/integrationTest/*.xml' }
      }
    }

    stage('Dependency Vulnerability Scan') {
      steps {
        script {
          // Trivy로 실행 JAR에 포함된 라이브러리의 알려진 취약점(CVE)을 검사한다.
          // 현재 정책: 보고 전용. HIGH/CRITICAL이 있으면 빌드를 UNSTABLE로 표시하고 배포는 계속한다 (docs/security-report.md).
          // Gate로 바꾸려면 아래 unstable(...)을 error(...)로 바꾼다.
          int findings = sh(script: 'ci/scripts/dependency-scan.sh', returnStatus: true)
          if (findings == 2) {
            unstable('HIGH/CRITICAL 취약점 발견 (build/reports/sca 참고)')
          } else if (findings != 0) {
            error("dependency scan failed (exit ${findings})")
          }
        }
      }
      post {
        always { archiveArtifacts artifacts: 'build/reports/sca/**', allowEmptyArchive: true }
      }
    }

    stage('Package') {
      steps {
        sh """
          docker build -t ${REGISTRY}/enterprise-integration-hub:${env.IMAGE_TAG} .
          docker build -t ${REGISTRY}/enterprise-integration-hub-gateway:${env.IMAGE_TAG} -f api-gateway/Dockerfile .
        """
      }
    }

    stage('Publish to Nexus') {
      steps {
        withCredentials([usernamePassword(credentialsId: 'nexus-credentials', usernameVariable: 'NEXUS_USER', passwordVariable: 'NEXUS_PASSWORD')]) {
          // JAR: Maven snapshots 저장소 (Gradle 속성은 환경 변수로 전달 → 명령줄에 비밀번호가 없음)
          withEnv(["ORG_GRADLE_PROJECT_nexusMavenUrl=${NEXUS_MAVEN_URL}"]) {
            sh 'ORG_GRADLE_PROJECT_nexusUsername="$NEXUS_USER" ORG_GRADLE_PROJECT_nexusPassword="$NEXUS_PASSWORD" ./gradlew --no-daemon publish'
          }
          // Docker 이미지: docker-hosted 저장소
          sh """
            echo "\$NEXUS_PASSWORD" | docker login ${REGISTRY} -u "\$NEXUS_USER" --password-stdin
            docker push ${REGISTRY}/enterprise-integration-hub:${env.IMAGE_TAG}
            docker push ${REGISTRY}/enterprise-integration-hub-gateway:${env.IMAGE_TAG}
          """
        }
      }
    }

    stage('Deploy to Dev') {
      steps {
        script {
          env.PREVIOUS_TAG = sh(script: 'cat "$DEPLOY_STATE" 2>/dev/null || true', returnStdout: true).trim()
          env.DEPLOY_STARTED = 'true'
          echo "deploy ${env.IMAGE_TAG} (previous: ${env.PREVIOUS_TAG ?: 'none'})"
          deployDev(env.IMAGE_TAG, COMPOSE)
        }
      }
    }

    stage('Healthcheck') {
      steps {
        sh 'ci/scripts/healthcheck.sh "$IMAGE_TAG"'
        // 검증까지 통과한 버전만 다음 Rollback 대상이 된다.
        sh 'mkdir -p "$(dirname "$DEPLOY_STATE")" && echo "$IMAGE_TAG" > "$DEPLOY_STATE"'
      }
    }
  }

  post {
    failure {
      script {
        // 배포 또는 Healthcheck에서 실패하면 마지막 정상 버전으로 되돌린다. 빌드 결과는 실패로 남긴다.
        if (env.DEPLOY_STARTED == 'true') {
          if (env.PREVIOUS_TAG) {
            echo "ROLLBACK: ${env.IMAGE_TAG} → ${env.PREVIOUS_TAG}"
            deployDev(env.PREVIOUS_TAG, COMPOSE)
            sh "ci/scripts/healthcheck.sh '${env.PREVIOUS_TAG}'"
            echo "ROLLBACK 완료: 개발 환경은 ${env.PREVIOUS_TAG}로 동작 중"
          } else {
            echo '이전 정상 버전이 없어 자동 Rollback을 하지 않았습니다. docs/cicd.md의 수동 절차를 따르세요.'
          }
        }
      }
    }
    always {
      sh 'rm -rf "$DOCKER_CONFIG"'
    }
  }
}
