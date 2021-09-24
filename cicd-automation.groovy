#!/bin/groovy

properties([[$class: 'JiraProjectProperty'], buildDiscarder(logRotator(artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '2', numToKeepStr: '10')), parameters([choice(choices: ['Yes', 'No'], description: '''<html>
<h6 style="color:DodgerBlue;">Do you want to run test?</h6>
</html>''', name: 'RunTest'), choice(choices: ['Yes', 'No'], description: '''<html>
<h6 style="color:DodgerBlue;">Do you want to run Sonarscan on the project?</h6>
</html>''', name: 'SonarScan'), choice(choices: ['Yes', 'No'], description: '''<html>
<h6 style="color:DodgerBlue;">Do you want to upload target jar to Nexus?</h6>
</html>''', name: 'UploadArtifact'), choice(choices: ['Yes', 'No'], description: '''<html>
<h6 style="color:DodgerBlue;">Do you want to create JIRA relase task?</h6>
</html>''', name: 'CreateReleaseTask'), choice(choices: ['Yes', 'No'], description: '''<html>
<h6 style="color:DodgerBlue;">Do you want to deploy it to Kubernetes environment?</h6>
</html>''', name: 'Deploy')])])

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

def versionDump() {
    try {
        String newVersion = getNewVersion()
        withMaven(maven: 'Maven') {
            sh """
                mvn -B --no-transfer-progress versions:set -DgenerateBackupPoms=false -DnewVersion=$newVersion
            """
        }
    }
    catch (err) {
        errorMsg("ERROR: ${err.message}!")
    }
}

String getPomVersion() {
    def matcher
    matcher = readFile('pom.xml') =~ '<version>(\\d*)\\.(\\d*)\\.(\\d*)-(\\d*)</version>'
    matcher ? matcher[0] : null
}

String getNewVersion() {
    oldVersion = readMavenPom().getVersion()
    oldPOMVersion = getPomVersion()
    def BUILD_NUMB = Integer.parseInt(oldPOMVersion[4]) + 1;
    newVersion = "${oldPOMVersion[1]}.${oldPOMVersion[2]}.${oldPOMVersion[3]}-${BUILD_NUMB}"
    return newVersion
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

def uploadArtifactToNexus() {
    String newVersion = getNewVersion()
    switch("${params.UploadArtifact}") {
        case "Yes":
            nexusArtifactUploader artifacts: [[artifactId: 'test-application', 
                classifier: '', 
                file: "target/*.jar", 
                type: 'jar']], 
                credentialsId: 'nexus-creds', 
                groupId: 'package', 
                nexusUrl: 'nexusurl', 
                nexusVersion: 'nexus3', 
                protocol: 'http', repository: 
                'automation', 
                version: newVersion
        break
        case "No":
            success("Skipped as upload artifact is Disabled.")
        break
    }
}

def deploy() {
    switch("${params.Deploy}") {
        case "Yes":
            try {
                sh "helm upgrade --install <release-name> <helm-name>"
                sleep(2)
                int count = 0;
                int iterations = 10;
                while(count < 10) {
                    if(count < iterations) {
                        status = sh(returnStdout: true, script:"""kubectl get pods | grep <release-name> | grep -vE "Evicted|Terminated" | grep 0/1 | cat""").trim()
                        if("${status}") {
                            sleep(30)
                            inProcess("<release-name> is not up")
                            count++;
                        }
                        else {
                            success("<release-name> is up")
                            break;
                        }
                    }
                    else {
                        System.exit(0);
                    }
                }
            }
            catch (err) {
                errorMsg("ERROR: ${err.message}!")
            }
        break
        case "No":
            success("Skipped as deployment is Disabled.")
        break
    }
}

def createJiraTicket() {
    switch("${params.CreateReleaseTask}") {
        case "Yes":
            withEnv(['JIRA_SITE=Jira']) {
                def testIssue = [fields: [ project: [key: 'CICD'],
                    summary: 'Build is ready for Release!',
                    issuetype: [name: 'Story']]]
                response = jiraNewIssue issue: testIssue
            }
        break
        case "No":
            success("Skipped creating Jira release task as it is Disabled.")
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
                versionDump()
                build()
            }
            stage ("Scanning code with SonarQube") {
                runSonarScan()
            }
            stage ("Quality Gate") {
                qualityGateCheck()
            }
            stage ("Upload package to Nexus") {
                uploadArtifactToNexus()
            }
            stage ("Deploy") {
                deploy()
            }
            stage ("Create JIRA ticket") {
                createJiraTicket()
            }
        }
    }
}
