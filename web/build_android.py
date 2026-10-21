"""web/app-body.html থেকে অ্যাপের ভেতরের পেজ (app/src/main/assets/www/index.html) বানায়।
Firebase আর ফন্ট অ্যাপের ভেতরেই থাকে, তাই প্রথমবার থেকেই নেট ছাড়া খুলবে।"""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WWW = os.path.join(ROOT, "app", "src", "main", "assets", "www")
body = open(os.path.join(ROOT, "web", "app-body.html"), encoding="utf-8").read()
gf = '<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Hind+Siliguri:wght@400;500;600;700&family=Tiro+Bangla&display=swap">'
assert gf in body
body = body.replace(gf, '<link rel="stylesheet" href="fonts/fonts.css">')

head = """<!doctype html>
<html lang="bn">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#7A1F24">
</head>
<body>
"""
open(os.path.join(WWW, "index.html"), "w", encoding="utf-8").write(head + body + "\n</body>\n</html>\n")
print("built", os.path.join(WWW, "index.html"))
