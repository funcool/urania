all: doc

doc:
	mkdir -p dist/latest/
	asciidoctor -o dist/latest/index.html docs/content.adoc

github: doc
	git commit -m "Generate documentation" dist/
	git add dist/
	git subtree push --prefix dist origin gh-pages
