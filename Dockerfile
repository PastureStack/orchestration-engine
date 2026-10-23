FROM golang:1.27.3-bookworm@sha256:972b4110ba0f47539a975c31e52c64765e1ff19f2d74469ae4a595c6676e80a5 AS docker-cli-build

ARG DOCKER_VERSION=29.7.2
ARG DOCKER_CLI_COMMIT=a7dcaa6fdb6ed04aacbfdc76357fdae01605609e
ADD --checksum=sha256:6e5c91d3a5a79db78cf989d07727d00e757aa0da4d135a3ce4b86061b83fb511 https://codeload.github.com/docker/cli/tar.gz/a7dcaa6fdb6ed04aacbfdc76357fdae01605609e /tmp/docker-cli.tar.gz
RUN set -eux; \
    mkdir -p /go/src/github.com/docker/cli; \
    tar -xzf /tmp/docker-cli.tar.gz -C /go/src/github.com/docker/cli --strip-components=1; \
    cd /go/src/github.com/docker/cli; \
    test "$(cat VERSION)" = "${DOCKER_VERSION}"; \
    test "$(go env GOVERSION)" = go1.27.3; \
    CGO_ENABLED=0 GO_STRIP=1 VERSION="${DOCKER_VERSION}" GITCOMMIT="${DOCKER_CLI_COMMIT}" SOURCE_DATE_EPOCH=1785922455 ./scripts/build/binary; \
    test -x build/docker-linux-amd64; \
    build/docker-linux-amd64 --version | grep -F "Docker version ${DOCKER_VERSION}"

FROM ubuntu:26.04@sha256:2260313b31c8c011cd2eebe728008efac1b3982be73eb71348ea2648d2c0e09b

COPY ubuntu-apt.lock /licenses/ubuntu-apt.lock
COPY scripts/install-python-security-tools.sh /usr/local/bin/install-python-security-tools
ADD --checksum=sha256:fac6f0728cf9490a7a698e6b343e4140283e92355b060193c04f5770a666df8a https://security.ubuntu.com/ubuntu/pool/main/p/python3.14/libpython3.14-minimal_3.14.4-1ubuntu0.2_amd64.deb /tmp/libpython3.14-minimal.deb
ADD --checksum=sha256:3c267ef0f902f763c3615cbf28dd6fe91d2e4f603075d7f03db99f8d4eb66f46 https://security.ubuntu.com/ubuntu/pool/main/p/python3.14/libpython3.14-stdlib_3.14.4-1ubuntu0.2_amd64.deb /tmp/libpython3.14-stdlib.deb
ADD --checksum=sha256:0a7ea8a52834f9b3de0f11758924f80f7a6ebcbb4b02a55ba4f457503569f55f https://security.ubuntu.com/ubuntu/pool/main/p/python3.14/python3.14-minimal_3.14.4-1ubuntu0.2_amd64.deb /tmp/python3.14-minimal.deb
ADD --checksum=sha256:780c308c47d8afcbb7d339f0023350e7cf60c1a37e9572a1f88774cc18f60526 https://security.ubuntu.com/ubuntu/pool/main/p/python3.14/python3.14_3.14.4-1ubuntu0.2_amd64.deb /tmp/python3.14.deb
ADD --checksum=sha256:cad2456bf3cb57cb18d2c82b48b549e5b4ac6eee9b3e14e65334209387e3d0ae https://security.ubuntu.com/ubuntu/pool/universe/p/python3.14/python3.14-venv_3.14.4-1ubuntu0.2_amd64.deb /tmp/python3.14-venv.deb
ADD --checksum=sha256:fd7f4dfa8190e014013b9f08f3e973f4e4c26ffd179a204895533a7254813a70 https://files.pythonhosted.org/packages/3f/06/3e4420302363fbb71be3c7f35ce042f54bfca330e3e66b3a2e3c4cca579f/pip-26.2.2-py3-none-any.whl /tmp/pip-26.2.2-py3-none-any.whl
ADD --checksum=sha256:42d35340cc5b9ba78e818ddbff0c5b1c2b779de448a340fd1b989f5fa8670e1b https://files.pythonhosted.org/packages/c4/51/29046e48f693c54e5b7412216d447a1d8362c9653148d98c02cdf65fa733/certifi-2026.10.19-py3-none-any.whl /tmp/certifi-2026.10.19-py3-none-any.whl
ADD --checksum=sha256:5b6326f1932cfd7be57130561dc78192f09ed9f58bfbe6606ed0c50510c3b6cd https://archive.ubuntu.com/ubuntu/pool/main/c/ca-certificates/ca-certificates_20260816ubuntu0.26.04.1_all.deb /tmp/ca-certificates.deb
ADD --checksum=sha256:2c418a61d7165d0cde01d9cb05ae025cc41fc75ef6d3f095996bd045ba0945dc https://security.ubuntu.com/ubuntu/pool/main/c/curl/curl_8.18.0-1ubuntu2.8_amd64.deb /tmp/curl.deb
ADD --checksum=sha256:8db913e1fa5a694ca6ba7b828bd18c5204e6853ac07fe7ed9c6e1f94e3444d8e https://security.ubuntu.com/ubuntu/pool/main/c/curl/libcurl3t64-gnutls_8.18.0-1ubuntu2.8_amd64.deb /tmp/libcurl3t64-gnutls.deb
ADD --checksum=sha256:7c0b37592f83a629cc22f0c98effa176da9edbd6d77b3f38184a9d0ddc5e0266 https://security.ubuntu.com/ubuntu/pool/main/c/curl/libcurl4t64_8.18.0-1ubuntu2.8_amd64.deb /tmp/libcurl4t64.deb

ENV DEBIAN_FRONTEND=noninteractive
ARG TEMURIN_JDK25_URL="https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/OpenJDK25U-jdk_x64_linux_hotspot_25.0.5_8.tar.gz"
ARG TEMURIN_JDK25_SHA256=68982529869257d0ee6b824ec5aa270ff8b118bb438670da0b97ebdab178d7fb
ARG MAVEN_VERSION=3.9.16
ARG MAVEN_SHA512=831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6
ARG DOCKER_VERSION=29.7.2
ENV JAVA_HOME=/opt/java/openjdk
ENV MAVEN_HOME=/opt/apache-maven
ENV PATH=${JAVA_HOME}/bin:${MAVEN_HOME}/bin:${PATH}
ENV VIRTUALENV_PIP=26.2.2 \
    VIRTUALENV_EXTRA_SEARCH_DIR=/usr/share/python-wheels \
    VIRTUALENV_NO_PERIODIC_UPDATE=1

LABEL org.opencontainers.image.source="https://github.com/PastureStack/orchestration-engine" \
      org.opencontainers.image.description="PastureStack Orchestration Engine build environment." \
      org.opencontainers.image.licenses="Apache-2.0"

RUN set -eux; \
    . /licenses/ubuntu-apt.lock; \
    rm -f /etc/apt/sources.list /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources; \
    rm -rf /tmp/ca-bootstrap; \
    mkdir -p /tmp/ca-bootstrap /etc/ssl/certs; \
    dpkg-deb --extract /tmp/ca-certificates.deb /tmp/ca-bootstrap; \
    find /tmp/ca-bootstrap/usr/share/ca-certificates -type f -name '*.crt' \
        | LC_ALL=C sort \
        | while IFS= read -r certificate; do sed -e '$a\' "${certificate}"; done \
        > /etc/ssl/certs/ca-certificates.crt; \
    test -s /etc/ssl/certs/ca-certificates.crt; \
    printf 'Types: deb\nURIs: https://snapshot.ubuntu.com/ubuntu/%s\nSuites: resolute resolute-updates resolute-backports resolute-security\nComponents: main universe restricted multiverse\nSigned-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\nSnapshot: no\n' \
        "${UBUNTU_APT_SNAPSHOT}" > /etc/apt/sources.list.d/pasturestack-snapshot.sources; \
    printf 'Acquire::Retries "5";\nAcquire::http::Timeout "30";\nAcquire::https::Timeout "30";\nAcquire::http::Pipeline-Depth "0";\nAcquire::https::CaInfo "/etc/ssl/certs/ca-certificates.crt";\nAcquire::https::Verify-Peer "true";\nAcquire::https::Verify-Host "true";\nAcquire::AllowInsecureRepositories "false";\nAPT::Get::AllowUnauthenticated "false";\n' > /etc/apt/apt.conf.d/80pasturestack-retries; \
    apt-get update; \
    apt-get upgrade -y; \
    apt-get install -y --no-install-recommends \
        bash="${UBUNTU_APT_BASH_VERSION}" \
        ca-certificates="${UBUNTU_APT_CA_CERTIFICATES_VERSION}" \
        curl="${UBUNTU_APT_CURL_BOOTSTRAP_VERSION}" \
        git="${UBUNTU_APT_GIT_VERSION}" \
        gzip="${UBUNTU_APT_GZIP_VERSION}" \
        iproute2="${UBUNTU_APT_IPROUTE2_VERSION}" \
        iptables="${UBUNTU_APT_IPTABLES_VERSION}" \
        make="${UBUNTU_APT_MAKE_VERSION}" \
        mariadb-client="${UBUNTU_APT_MARIADB_CLIENT_VERSION}" \
        postgresql-client="${UBUNTU_APT_POSTGRESQL_CLIENT_VERSION}" \
        procps="${UBUNTU_APT_PROCPS_VERSION}" \
        python3="${UBUNTU_APT_PYTHON3_VERSION}" \
        python3.14="${UBUNTU_APT_PYTHON3_14_VERSION}" \
        python3-pip="${UBUNTU_APT_PYTHON3_PIP_VERSION}" \
        python3-venv="${UBUNTU_APT_PYTHON3_VENV_VERSION}" \
        tar="${UBUNTU_APT_TAR_VERSION}" \
        tox="${UBUNTU_APT_TOX_VERSION}" \
        unzip="${UBUNTU_APT_UNZIP_VERSION}" \
        xz-utils="${UBUNTU_APT_XZ_UTILS_VERSION}"; \
    printf 'Types: deb\nURIs: https://snapshot.ubuntu.com/ubuntu/%s\nSuites: resolute-security\nComponents: main\nSigned-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\nSnapshot: no\n' \
        "${UBUNTU_APT_SECURITY_SNAPSHOT}" > /etc/apt/sources.list.d/pasturestack-security.sources; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        libssl3t64="${UBUNTU_APT_OPENSSL_VERSION}" \
        openssl="${UBUNTU_APT_OPENSSL_VERSION}" \
        openssl-provider-legacy="${UBUNTU_APT_OPENSSL_VERSION}"; \
    for package in libssl3t64 openssl openssl-provider-legacy; do \
        test "$(dpkg-query -W -f='${Version}' "${package}")" = "${UBUNTU_APT_OPENSSL_VERSION}"; \
    done; \
    for package in curl libcurl3t64-gnutls libcurl4t64; do \
        test "$(dpkg-deb -f "/tmp/${package}.deb" Package)" = "${package}"; \
        test "$(dpkg-deb -f "/tmp/${package}.deb" Version)" = "${UBUNTU_APT_CURL_VERSION}"; \
        test "$(dpkg-deb -f "/tmp/${package}.deb" Architecture)" = amd64; \
    done; \
    dpkg -i /tmp/curl.deb /tmp/libcurl3t64-gnutls.deb /tmp/libcurl4t64.deb; \
    dpkg -i /tmp/ca-certificates.deb; \
    test "$(dpkg-query -W -f='${Version}' ca-certificates)" = 20260816ubuntu0.26.04.1; \
    bash /usr/local/bin/install-python-security-tools; \
    for package in curl libcurl3t64-gnutls libcurl4t64; do \
        test "$(dpkg-query -W -f='${Version}' "${package}")" = "${UBUNTU_APT_CURL_VERSION}"; \
    done; \
    { \
        printf 'snapshot\t%s\n' "${UBUNTU_APT_SNAPSHOT}"; \
        printf 'security-snapshot\t%s\n' "${UBUNTU_APT_SECURITY_SNAPSHOT}"; \
        dpkg-query -W -f='${binary:Package}\t${Version}\n' | LC_ALL=C sort; \
    } > /licenses/ORCHESTRATION-ENGINE-UBUNTU-APT-PACKAGES.tsv; \
    apt-get clean; \
    rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/* /usr/bin/pebble

COPY --from=docker-cli-build /go/src/github.com/docker/cli/build/docker-linux-amd64 /usr/bin/docker

RUN mkdir -p ${JAVA_HOME} /usr/lib/jvm && \
    curl -fsSL --retry 5 --retry-all-errors --retry-delay 2 --connect-timeout 10 --max-time 600 -o /tmp/temurin-jdk25.tar.gz "${TEMURIN_JDK25_URL}" && \
    echo "${TEMURIN_JDK25_SHA256}  /tmp/temurin-jdk25.tar.gz" | sha256sum -c - && \
    tar -xzf /tmp/temurin-jdk25.tar.gz -C ${JAVA_HOME} --strip-components=1 && \
    rm -f /tmp/temurin-jdk25.tar.gz && \
    ln -sfn ${JAVA_HOME} /usr/lib/jvm/temurin-25-amd64 && \
    curl -fsSL --retry 5 --retry-all-errors --retry-delay 2 --connect-timeout 10 --max-time 300 -o /tmp/apache-maven.tar.gz "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" && \
    echo "${MAVEN_SHA512}  /tmp/apache-maven.tar.gz" | sha512sum -c - && \
    mkdir -p ${MAVEN_HOME} && \
    tar -xzf /tmp/apache-maven.tar.gz -C ${MAVEN_HOME} --strip-components=1 && \
    rm -f /tmp/apache-maven.tar.gz && \
    test "$(find ${MAVEN_HOME}/lib -maxdepth 1 -name 'plexus-utils-*.jar' -printf '%f\n')" = 'plexus-utils-3.6.1.jar' && \
    chmod +x /usr/bin/docker && \
    java -version 2>&1 | grep -F '25.0.5' && \
    test "$(javac -version 2>&1)" = 'javac 25.0.5' && \
    mvn -version | grep -F 'Apache Maven 3.9.16' && \
    test "$(python3 --version)" = 'Python 3.14.4' && \
    tox --version | grep -F '4.33.0' && \
    docker --version | grep -F 'Docker version 29.7.2'

WORKDIR /workspace

CMD ["mvn", "-B", "-DskipTests", "package"]
