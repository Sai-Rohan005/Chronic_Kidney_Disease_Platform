PYTHON ?= python3
.PHONY: eval test-eval

eval:
	$(PYTHON) evaluation/evaluate.py

test-eval:
	$(PYTHON) -m unittest discover -s tests -p test_evaluation.py -v
