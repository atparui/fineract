pipeline {
    agent any

    environment {
        DOCKER_REGISTRY = 'docker.io'
        DOCKER_IMAGE = 'shivain22/fineract'
        DOCKER_USERNAME = 'shivain22'
        DOCKER_PASSWORD = 'Asd!@#123'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Setup') {
            steps {
                sh 'chmod +x ./gradlew 2>/dev/null || true'
            }
        }

        stage('Build') {
            steps {
                sh './gradlew :fineract-provider:bootJar :fineract-provider:resolve -x test --no-daemon'
            }
        }

        stage('Jib Build & Push') {
            steps {
                script {
                    sh """
                        ./gradlew :fineract-provider:jib \
                            -Djib.to.image=${DOCKER_REGISTRY}/${DOCKER_IMAGE} \
                            -Djib.to.auth.username=${DOCKER_USERNAME} \
                            -Djib.to.auth.password=${DOCKER_PASSWORD} \
                            --no-daemon
                    """
                }
            }
        }

        stage('Deploy to Platform') {
            steps {
                sh 'cd /platform && ./scripts/down.sh dev fineract'
                sh """
                    cd /platform && \
                    FINERACT_IMAGE_OVERRIDE=${DOCKER_REGISTRY}/${DOCKER_IMAGE} \
                    FINERACT_IMAGE_TAG_OVERRIDE=latest \
                    ./scripts/up.sh dev fineract
                """
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: 'fineract-provider/build/libs/*.jar', fingerprint: true, allowEmptyArchive: true
        }
        success {
            echo '✅ Fineract pipeline succeeded!'
        }
        failure {
            echo '❌ Fineract pipeline failed!'
        }
    }
}
