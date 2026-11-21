.PHONY: eval test-eval

eval:
	$(MAKE) -C services/ml-service eval

test-eval:
	$(MAKE) -C services/ml-service test-eval
