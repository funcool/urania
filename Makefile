all: doc

doc:
	mkdir -p dist/latest/
	asciidoctor -o dist/latest/index.html docs/content.adoc

github: doc
	git add dist/
	git commit -m "Generate documentation" --allow-empty dist/
	git subtree push --prefix dist origin gh-pages
