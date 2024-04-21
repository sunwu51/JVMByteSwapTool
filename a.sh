pwd
ls
for zipfile in *; do
  if [[ -f "$zipfile" ]]; then
    echo "Uploading $zipfile"
  fi
done