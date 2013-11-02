/*
* Artifactory is a binaries repository manager.
* Copyright (C) 2013 JFrog Ltd.
*
* Artifactory is free software: you can redistribute it and/or modify
* it under the terms of the GNU Lesser General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* Artifactory is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License
* along with Artifactory.  If not, see <http://www.gnu.org/licenses/>.
*/


import groovy.transform.Field
import groovyx.net.http.HTTPBuilder
import org.artifactory.repo.RepoPath
import org.artifactory.repo.RepoPathFactory
import org.artifactory.resource.ResourceStreamHandle
import org.bouncycastle.openpgp.*

import static groovyx.net.http.ContentType.BINARY
import static groovyx.net.http.Method.GET

@Field repos

/**
 * Verifies downloaded files against their asc signature, by using the signature's public key from a public key server
 * (currently using http://keys.gnupg.net). For remote repos, tries to fetch the .asc signature.
 * Result is cached in the 'pgp-verified' property on artifacts, so that subsequent checks are cheap.
 * Artifacts that have not been verified will cause a 403 forbidden response to be returned to downloaders.
 *
 * @author Yoav Landman
 */
download {
    repos = initRepos()

    beforeDownloadRequest { request, repoPath ->
        if (!repos.contains(repoPath.repoKey)) {
            return
        }
        if (isSignatureFile(repoPath)) {
            log.debug("Ignoring asc fetch for: ${repoPath}")
            return
        }
        RepoPath ascRepoPath = RepoPathFactory.create(repoPath.repoKey, "${repoPath.path}.asc")
        if (!repositories.exists(ascRepoPath)) {
            //Try to fetch the asc in case of a remote by issuing a request to self
            def type = repositories.getRepositoryConfiguration(ascRepoPath.repoKey).type
            if (type == 'remote') {
                fetchAsc(ascRepoPath, request)
            }
        }
    }

    altResponse { request, responseRepoPath ->
        if (!repos.contains(request.repoPath.repoKey)) {
            return
        }
        if (isSignatureFile(responseRepoPath)) {
            log.debug("Ignoring download verification for: ${responseRepoPath}")
            return
        }
        if (!repositories.exists(responseRepoPath)) {
            //Upon initial download into cache content is not seen yet
            return
        }
        def verified = repositories.getProperties(responseRepoPath).getFirst('pgp-verified')
        if (verified == null) {
            def verifyResult
            try {
                verifyResult = verify(responseRepoPath)
            } catch (Exception e) {
                log.error("Verification error for artifact $responseRepoPath.", e)
            }

            //Update the property
            repositories.setProperty(responseRepoPath, 'pgp-verified', verifyResult ? '1' : '0')

            if (!verifyResult) {
                log.warn "Non-verified artifact request: $responseRepoPath"
                status = 403
                message = 'Artifact has not been verified yet and cannot be downloaded.'
            } else {
                log.info "Successfully verified artifact: $responseRepoPath"
            }
        } else if (verified == '1') {
            log.debug "Already verified artifact: $responseRepoPath"
        } else if (verified == '0') {
            log.debug "Badly verified artifact: $responseRepoPath"
            status = 403
            message = 'Artifact failed verification and cannot be downloaded.'
        }
    }
}

@Grapes([
@Grab(group = 'org.codehaus.groovy.modules.http-builder', module = 'http-builder', version = '0.6'),
@Grab(group = 'org.bouncycastle', module = 'bcpg-jdk16', version = '1.46'),
@GrabExclude('commons-codec:commons-codec'),
@GrabResolver(name = 'jcenter', root = 'http://jcenter.bintray.com')
])

def verify(rp) {
    RepoPath ascRepoPath = RepoPathFactory.create(rp.repoKey, "${rp.path}.asc")
    if (repositories.exists(ascRepoPath)) {
        //Get the public key id from the asc
        ResourceStreamHandle asc = repositories.getContent(ascRepoPath)
        PGPSignature signature
        def hexPublicKeyId
        try {
            signature = getSignature(asc)
            hexPublicKeyId = Long.toHexString(signature.getKeyID())
            log.debug "Found public key: $hexPublicKeyId"
        } finally {
            asc.close()
        }

        //Try to get the public key for the detached asc signature
        def http = new HTTPBuilder("http://keys.gnupg.net/pks/lookup?op=get&search=0x$hexPublicKeyId")

        def verified = http.request(GET, BINARY) { req ->
            response.success = { resp, inputStream ->
                PGPObjectFactory factory = new PGPObjectFactory(PGPUtil.getDecoderStream(inputStream))
                def o
                while ((o = factory.nextObject()) != null) {
                    if (o instanceof PGPPublicKeyRing) {
                        def keys = o.getPublicKeys()
                        while (keys.hasNext()) {
                            PGPPublicKey key = keys.next()
                            signature.initVerify(key, PGPUtil.defaultProvider)
                            ResourceStreamHandle file = repositories.getContent(rp)
                            try {
                                byte[] buffer = new byte[8192];
                                def n = 0
                                while (-1 != (n = file.inputStream.read(buffer))) {
                                    signature.update(buffer, 0, n)
                                }
                                if (signature.verify()) {
                                    inputStream.close()
                                    return true
                                }
                            } catch (Exception e) {
                                log.error("Failed to verify key ${Long.toHexString(key.getKeyID())}", e)
                            } finally {
                                file.close()
                            }
                        }
                    }
                }
                false
            }

            response.'404' = { resp ->
                log.debug("No public key found for $rp")
                false
            }

            response.failure = { resp ->
                throw Exception("Could not verify $rp: $resp.status - $resp.statusLine")
            }
        }

        if (verified) {
            log.info "Artifact $rp successfully verified!"
        }
        verified
    }
}

private void fetchAsc(RepoPath ascRepoPath, request) {
    def key = ascRepoPath.repoKey.endsWith('-cache') ? ascRepoPath.repoKey[0..-7] : ascRepoPath.repoKey
    def http = new HTTPBuilder("${request.servletContextUrl}/$key/$ascRepoPath.path")
    http.request(GET, BINARY) { req ->
        response.success = {
            log.info("Downloaded $ascRepoPath")
        }
        response.failure = { resp ->
            log.warn("Could not downloaded $ascRepoPath")
        }
    }
}

private PGPSignature getSignature(ResourceStreamHandle asc) {
    PGPObjectFactory factory = new PGPObjectFactory(PGPUtil.getDecoderStream(asc.inputStream))
    Object o = factory.nextObject();
    if (o instanceof PGPSignatureList) {
        ((PGPSignatureList) o).get(0)
    } else {
        throw IllegalArgumentException("Bad signature")
    }
}

private boolean isSignatureFile(responseRepoPath) {
    responseRepoPath.path.endsWith('.asc') || responseRepoPath.path.endsWith(".sha1") || responseRepoPath.path.endsWith(".md5")
}

private Set initRepos() {
    def file = new File(ctx.artifactoryHome.pluginsDir, 'pgpVerify.properties')
    file.exists() ? new ConfigSlurper().parse(file.toURI().toURL()).repos as Set : Collections.emptySet()
}