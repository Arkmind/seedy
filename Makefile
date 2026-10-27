# Shortcuts for building Seedy and running the local test server. `make` lists them.
# The scripts behind them work on their own too: build.sh, dev/make-media.sh, dev/make-music-books.py, dev/setup-*.py.

SHELL := /usr/bin/env bash
.DEFAULT_GOAL := help

JELLYFIN_IMAGE := $(shell sed -n 's/^ *image: \(jellyfin\/jellyfin:.*\)/\1/p' dev/docker-compose.yml)
PYTHON_IMAGE := python:3.12-slim

.PHONY: help build typecheck clean web web-mock android android-shared android-test android-shots dev-up dev-down dev-logs dev-install dev-media dev-libraries dev-seerr dev-arr dev-media-requests

help:
	@awk 'BEGIN { FS = ":.*## " } /^## / { printf "\n%s\n", substr($$0, 4) } /^[a-z-]+:.*## / { printf "  \033[1m%-19s\033[0m %s\n", $$1, $$2 }' $(MAKEFILE_LIST)

## Build

build: ## Build the plugin into artifacts/ (seedy_<version>.zip and manifest.json)
	./build.sh

typecheck: ## Typecheck the web app
	cd web && bun install --frozen-lockfile >/dev/null && bun run typecheck

clean: ## Remove build output
	rm -rf artifacts web/dist src/Jellyfin.Plugin.Seedy/bin src/Jellyfin.Plugin.Seedy/obj src/Jellyfin.Plugin.Seedy/Web

## Web app

web: ## Run the web app with Vite (http://127.0.0.1:5173/seedy/), proxied to the test server
	cd web && bun run dev

web-mock: ## Same, answering /Seedy/* from fixtures (no plugin needed)
	cd web && SEEDY_MOCK=1 bun run dev

## Android apps (android/: one APK for phones and Android TV)

android: ## Build the debug APK (android/app/build/outputs/apk/debug/)
	cd android && ./gradlew :app:assembleDebug

android-shared: ## Copy the web tokens and French dictionaries into the Android project
	bun scripts/android-shared.ts

android-test: ## Run the Android unit tests and compare screens with the recorded screenshots
	cd android && ./gradlew testDebugUnitTest verifyRoborazziDebug

android-shots: ## Re-record the Android screenshots (android/*/src/test/screenshots/)
	cd android && ./gradlew recordRoborazziDebug

## Test server (dev/docker-compose.yml: Jellyfin :8096, Seerr :5055, Sonarr :8989 and :8990 (4K), Radarr :7878, Lidarr :8686, Shelfarr :5056, Librarr :5050)

dev-up: ## Start the test server
	cd dev && docker compose up -d

dev-down: ## Stop the test server
	cd dev && docker compose down

dev-logs: ## Follow the test Jellyfin's log
	docker logs -f --tail 100 seedy-jellyfin

dev-install: ## Build, install into the test server and restart it
	./build.sh --install-dev

dev-media: ## Fill the test library: open-licensed films and shows, made-up music, books, audiobooks and comics
	docker run --rm -v "$(CURDIR)/dev/make-media.sh:/make-media.sh:ro" -v "$(CURDIR)/dev/data/media:/media" \
		--entrypoint /bin/sh $(JELLYFIN_IMAGE) /make-media.sh
	docker run --rm -v "$(CURDIR)/dev/make-music-books.py:/gen.py:ro" -v "$(CURDIR)/dev/data/media:/media" \
		-v "$(CURDIR)/android/ui/src/main/res/font/noto_sans.ttf:/font.ttf:ro" $(PYTHON_IMAGE) \
		sh -c 'pip install -q --disable-pip-version-check --root-user-action=ignore pillow numpy && python /gen.py'
	docker run --rm -v "$(CURDIR)/dev/data/media:/media" --entrypoint /bin/sh $(JELLYFIN_IMAGE) /media/.seedy-audio/encode.sh

dev-libraries: ## Add the Music and Books libraries, with plays, playlists and reading positions for Alex
	cd dev && python3 setup-libraries.py

dev-seerr: ## Connect the test Seerr to the test Jellyfin
	cd dev && python3 setup-seerr.py

dev-arr: ## Connect the test Sonarr, Sonarr 4K and Radarr to the test Seerr (after dev-seerr)
	cd dev && python3 setup-arr.py

dev-media-requests: ## Set up the test Lidarr, Shelfarr and Librarr, connect Seedy to them, add sample requests (after dev-install)
	cd dev && python3 setup-media-requests.py && python3 setup-seedy-requests.py

# Personal targets (a deploy to your own server, say) can go in local.mk, which git ignores.
-include local.mk
