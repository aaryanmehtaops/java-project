def codeCheckout() {
    cleanWs()
    checkout scm
    success("Workspace clean and code checkout.")
}

def runTestIfAny() {
    try {
        switch("${params.RunTest}") {
            case "Yes":
                withMaven(maven: 'Maven') {
                    sh 'mvn clean test'
                }
            break
            case "No":
                success("Unit testing is disabled.")
            break
        }
    }
    catch (err) {
        errorMsg("ERROR: ${err.message}!")
    }
}

def runSonarScan() {
    switch("${params.SonarScan}") {
        case "Yes":
            withSonarQubeEnv(credentialsId: 'SonarQube') {
                withMaven(maven: 'Maven') {
                    sh 'mvn sonar:sonar'
                }
            }
        break
        case "No":
            success("Sonar scan is Disabled.")
        break
    }
}

def qualityGateCheck() {
    switch("${params.SonarScan}") {
        case "Yes":
            def count = 0
            while (count < 5) {
                try {
                    timeout(time: 20, unit: 'SECONDS') {
                        withSonarQubeEnv(credentialsId: 'SonarQube') {
                            def qG = waitForQualityGate()
                            if (qG.status != 'OK') {
                                error("Quality Gate Failed, Returned $qG.status !!")
                            }
                        }
                    }
                    success("Quality Gate for Passed :)")
                    break
                } catch (e) {
                    count++
                } finally {
                    if (count >= 5) {
                        errorMsg("Quality Gate for failed after $count Retries!!")
                    }
                }
            }
        break
        case "No":
            success("Skipping quality gate validation as Sonar scan is disabled.")
        break
    }
}

def build() {
    try {
        withMaven(maven: 'Maven') {
            sh 'mvn clean install'
        }
    }
    catch (err) {
        errorMsg("ERROR: ${err.message}!")
    }

}

def uploadArtifactToArtifactory() {
    String newVersion = getNewVersion()
    switch("${params.UploadArtifact}") {
        case "Yes":
            httpRequest authentication: 'artifactory-creds', httpMode: 'PUT', ignoreSslErrors: true, responseHandle: 'NONE', uploadFile: 'abc.sh', url: 'http://10.10.29.102:8082/artifactory/jenkins-packages/abc.sh', wrapAsMultipart: false
        break
        case "No":
            success("Skipped as upload artifact is Disabled.")
        break
    }
}

def errorMsg(String text) {
    echo "\u001B[1m\033[31m${text}\u001B[m"
    currentBuild.result = "FAILED"
    error(text)
}

def success(String text) {
    echo "\u001B[1m\033[32m${text}\u001B[m"
}

node('master') {
    timeout (time: 1, unit: 'HOURS') {
        ansiColor('xterm') {
            stage ("Code Checkout") {
                codeCheckout()
            }
            stage ("Run Test") {
                runTestIfAny()
            }
            stage ("Build Project") {
                build()
            }
            stage ("Scanning code with SonarQube") {
                runSonarScan()
            }
            stage ("Quality Gate") {
                qualityGateCheck()
            }
            stage ("Upload package to Artifactory") {
                uploadArtifactToArtifactory()
            }
        }
    }
}
