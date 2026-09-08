ARG ES_VERSION=8.17.6
FROM docker.elastic.co/elasticsearch/elasticsearch:${ES_VERSION}

# smartcn is required by the kb_chunk.content mapping. Its version is tied to ES.
RUN bin/elasticsearch-plugin install --batch analysis-smartcn
