function slug(value) {
  return value.trim().toLowerCase().replace(" ", "-");
}

module.exports = { slug };
