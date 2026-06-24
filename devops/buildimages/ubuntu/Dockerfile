ARG BASE_IMAGE
FROM ${BASE_IMAGE}

RUN apt-get install -qq -y python3-pip python3-setuptools && \
    python3 -m pip install setuptools-scm==5.0.2 flake8==3.9.0 pytest-runner==5.3.0 && \
    update-alternatives --install /usr/bin/python python /usr/bin/python3 30

RUN unlink /opt/maven && \
    mkdir /opt/maven && \
    curl -s http://dfaf.ftc.hcocto.hpecorp.net/artifactory/buildmachine-files-copy/cross-linux/apache-maven-3.8.6-bin.tar.gz | tar -xz --strip-components=1 -C /opt/maven
