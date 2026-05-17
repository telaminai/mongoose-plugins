## To preview locally:                                                      
python3 -m venv .venv                                                                                                                                                                                                            
source .venv/bin/activate                                                                                                                                                                                                        
pip install -r docs-requirements.txt                                                                                                                                                                                             
mkdocs serve                                                                                                                                                                                                                     
Then http://127.0.0.1:8000.

## To go live:
1. Push to GitHub (when you're ready).
2. Settings → Pages → Source → gh-pages / / (root) in the repo.
3. The next push to main that touches docs/ or mkdocs.yml deploys; or run mkdocs gh-deploy --force once locally to seed.
