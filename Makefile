clean: 
	git clean -Xdf 

compile:
	scala-cli --power compile . \
		--build-info

# For testing purposes, changed if needed ⚠️
PATH_ARG := test_dir/
# For testing purposes, change if needed ⚠️
CMD_ARG := ls
run: 
	scala-cli --power run . \
		--build-info \
		-- \
		--path $(PATH_ARG) \
		--cmd $(CMD_ARG)
