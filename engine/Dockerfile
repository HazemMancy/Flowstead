# Flowstead engine image. SPDX-License-Identifier: GPL-3.0-or-later
# Build:  docker build -t flowstead-engine engine/
# Spike:  docker run --rm flowstead-engine dotnet /app/Flowstead.Engine.Spike.dll

FROM mcr.microsoft.com/dotnet/sdk:8.0 AS build
RUN apt-get update && apt-get install -y --no-install-recommends binutils zstd curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY scripts/ scripts/
# Reuse a pre-downloaded package if the build context has one (CI cache), else download.
COPY .dl/ .dl/
RUN scripts/fetch-dwsim.sh /opt/dwsim
COPY Directory.Build.props ./
COPY src/ src/
RUN dotnet publish src/Flowstead.Engine.Spike -c Release -o /app -p:DwsimBin=/opt/dwsim

FROM mcr.microsoft.com/dotnet/aspnet:8.0
RUN apt-get update && apt-get install -y --no-install-recommends libfontconfig1 libgomp1 \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home /var/lib/flowstead flowstead
COPY --from=build /opt/dwsim /opt/dwsim
COPY --from=build /app /app
ENV DWSIM_HOME=/opt/dwsim
USER flowstead
WORKDIR /app
CMD ["dotnet", "/app/Flowstead.Engine.Spike.dll"]
