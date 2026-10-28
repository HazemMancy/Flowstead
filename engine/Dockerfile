# ThermoDock engine image. SPDX-License-Identifier: GPL-3.0-or-later
# Build:  docker build -t thermodock-engine engine/
# Run:    see deploy/docker-compose.yml (Caddy terminates HTTPS in front of this container)
# Keys:   docker exec <container> dotnet /app/ThermoDock.Engine.Api.dll keys add <device>

FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
RUN apt-get update && apt-get install -y --no-install-recommends binutils zstd curl ca-certificates gcc libc6-dev \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY scripts/ scripts/
COPY native/ native/
# Reuse a pre-downloaded package if the build context has one (CI cache), else download.
COPY .dl/ .dl/
RUN scripts/fetch-dwsim.sh /opt/dwsim
COPY Directory.Build.props ./
COPY src/ src/
RUN dotnet publish src/ThermoDock.Engine.Worker -c Release -o /app/worker -p:DwsimBin=/opt/dwsim \
 && dotnet publish src/ThermoDock.Engine.Api -c Release -o /app \
 && dotnet publish src/ThermoDock.Engine.Spike -c Release -o /app/spike -p:DwsimBin=/opt/dwsim

FROM mcr.microsoft.com/dotnet/aspnet:8.0
RUN apt-get update && apt-get install -y --no-install-recommends libfontconfig1 libgomp1 \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home /var/lib/flowstead thermodock \
    && mkdir -p /var/lib/flowstead && chown thermodock /var/lib/flowstead
COPY --from=build /opt/dwsim /opt/dwsim
COPY --from=build /app /app
ENV DWSIM_HOME=/opt/dwsim \
    ASPNETCORE_HTTP_PORTS=8080 \
    Engine__WorkerPath=/app/worker/ThermoDock.Engine.Worker.dll \
    Engine__DatabasePath=/var/lib/flowstead/flowstead.db
# Data path kept from the Flowstead name (D66): existing volumes keep their API keys.
VOLUME /var/lib/flowstead
USER thermodock
WORKDIR /app
EXPOSE 8080
ENTRYPOINT ["dotnet", "/app/ThermoDock.Engine.Api.dll"]
