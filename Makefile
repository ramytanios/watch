clean: 
	git clean -Xdf 

compile:
	scala-cli --power compile . \
		--build-info

package:
	scala-cli --power package . \
		--build-info \
		-o watch-and-rexec

#NOTE: currently not working
package-graal: 
	scala-cli --power package . \
		--build-info \
		-o watch-and-exec \
		--native-image

#NOTE: currently not working
package-native: 
	scala-cli --power package . \
		--build-info \
		--native \
		-o watch-and-exec \
