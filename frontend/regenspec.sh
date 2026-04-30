cd ../typespec && tsp compile . && cp tsp-output/schema/openapi.yaml ../frontend/spec/ && cd ../frontend && npm run gen:api
