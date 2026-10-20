# Shortcuts for building Seedy and running the local test server. `make` lists them.
# The scripts behind them work on their own too: build.sh, dev/make-media.sh, dev/setup-seerr.py, dev/setup-arr.py.

SHELL := /usr/bin/env bash
.DEFAULT_GOAL := help

JELLYFIN_IMAGE := $(shell sed -n 's/^ *image: \(jellyfin\/jellyfin:.*\)/\1/p' dev/docker-compose.yml)

.PHONY: help build typecheck clean web web-mock dev-up dev-down dev-logs dev-install dev-media dev-seerr dev-arr

help:
	@awk 'BEGIN { FS = ":.*## " } /^## / { printf "\n%s\n", substr($$0, 4) } /^[a-z-]+:.*## / { printf "  \033[1m%-12s\033[0m %s\n", $$1, $$2 }' $(MAKEFILE_LIST)

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

## Test server (dev/docker-compose.yml: Jellyfin on :8096, Seerr on :5055, Sonarr on :8989 and :8990 (4K), Radarr on :7878)

dev-up: ## Start the test server
	cd dev && docker compose up -d

dev-down: ## Stop the test server
	cd dev && docker compose down

dev-logs: ## Follow the test Jellyfin's log
	docker logs -f --tail 100 seedy-jellyfin

dev-install: ## Build, install into the test server and restart it
	./build.sh --install-dev

dev-media: ## Fill the test library with open-licensed titles (dev/make-media.sh)
	docker run --rm -v "$(CURDIR)/dev/make-media.sh:/make-media.sh:ro" -v "$(CURDIR)/dev/data/media:/media" \
		--entrypoint /bin/sh $(JELLYFIN_IMAGE) /make-media.sh

dev-seerr: ## Connect the test Seerr to the test Jellyfin
	cd dev && python3 setup-seerr.py

dev-arr: ## Connect the test Sonarr, Sonarr 4K and Radarr to the test Seerr (after dev-seerr)
	cd dev && python3 setup-arr.py

# Personal targets (a deploy to your own server, say) can go in local.mk, which git ignores.
-include local.mk
