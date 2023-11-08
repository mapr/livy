ARG BASE_IMAGE
FROM ${BASE_IMAGE}

RUN apt install -qq -y python3-pip python3-setuptools && \
    pip3 install setuptools-scm==5.0.2 flake8==3.9.0 pytest-runner==5.3.0 && \
    update-alternatives --install /usr/bin/python python /usr/bin/python3 30
