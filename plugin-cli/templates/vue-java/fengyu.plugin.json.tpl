{
  "schemaVersion": 1,
  "ui": {
    "root": "ui-src",
    "output": "dist",
    "install": ["npm", "ci"],
    "test": ["npm", "test"],
    "build": ["npm", "run", "build"]
  },
  "worker": {
    "root": "worker",
    "test": ["maven", "test"],
    "build": ["maven", "package", "-DskipTests"],
    "artifact": "target/{{javaClassPrefix}}-worker.jar",
    "mainClass": "{{javaPackage}}.{{javaClassPrefix}}WorkerMain"
  },
  "package": { "outputDirectory": "dist-package" }
}
