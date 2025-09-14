#!/data/data/com.termux/files/usr/bin/bash
if ! command -v git &> /dev/null; then
  pkg update && pkg install git -y
fi
read -p "GitHub repo URL (https://github.com/USER/REPO.git): " REPO
git init
git add .
git commit -m "Initial commit - ScreenShare final"
git branch -M main
git remote add origin ${REPO}
git push -u origin main
