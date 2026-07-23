.RECIPEPREFIX := >
TARGETS := $(shell ls scripts)

DAPPER_IMAGE ?= rc16-cattle-dapper:ubuntu26
DAPPER_SOURCE ?= /usr/src/cattle
DOCKER_VERSION ?= 29.4.2
DOCKER_BUILD_NETWORK ?= default
UBUNTU_MIRROR ?= http://archive.ubuntu.com/ubuntu

.dapper:
>docker build \
>  --network=$(DOCKER_BUILD_NETWORK) \
>  --build-arg DOCKER_VERSION=$(DOCKER_VERSION) \
>  --build-arg UBUNTU_MIRROR=$(UBUNTU_MIRROR) \
>  -t $(DAPPER_IMAGE) \
>  -f Dockerfile.dapper .

$(TARGETS): .dapper
>docker run --rm --privileged \
>  -v $(CURDIR):$(DAPPER_SOURCE) \
>  -v /var/run/docker.sock:/var/run/docker.sock \
>  -v $(HOME)/.m2:/root/.m2 \
>  -e DAPPER_UID=$$(id -u) \
>  -e DAPPER_GID=$$(id -g) \
>  -e API_VERSION \
>  -e ENVIRONMENTS \
>  -e MAVEN_ARGS \
>  -e MAVEN_TARGET \
>  -e RELEASE_ARGS \
>  -e DOCKER_VERSION=$(DOCKER_VERSION) \
>  $(DAPPER_IMAGE) $@

.DEFAULT_GOAL := ci

.PHONY: .dapper $(TARGETS)
