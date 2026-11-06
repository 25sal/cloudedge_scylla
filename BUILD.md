# BUILD on Fedora Linux
## Install dependence

- Install maven 
``` bash
   sudo dnf install maven
```
- Install the java dependecies using maven
``` bash
 mvn org.apache.maven.plugins:maven-install-plugin:2.4:install-file -Dfile=lib/OpenXES-20160212.jar -DgroupId=org.deckfour -DartifactId=openxes -Dversion=20160212 -Dpackaging=jar -DgeneratePom=true 

 mvn org.apache.maven.plugins:maven-install-plugin:2.4:install-file -Dfile=lib/OpenXES-XStream-20160212.jar -DgroupId=org.deckfour -DartifactId=openxes-xstream -Dversion=20160212 -Dpackaging=jar -DgeneratePom=true 

 mvn org.apache.maven.plugins:maven-install-plugin:2.4:install-file -Dfile=lib/Spex.jar -DgroupId=org.deckfour -DartifactId=ospex -Dversion=1.0 -Dpackaging=jar -DgeneratePom=true 
 
mvn org.apache.maven.plugins:maven-install-plugin:2.4:install-file -Dfile=lib/desmoj-2.5.1e-bin.jar -DgroupId=desmoj -DartifactId=desmoj -Dversion=2.5.1e -Dpackaging=jar -DgeneratePom=true 
```
- Build all
``` bash
mvn clean package
```

## Rename plugins
 Plugin jar points to an external file but it does not work.
 Rename the file to avoid errors. 
 ``` bash
 mv plugins/PluginTest-0.0.1-SNAPSHOT.jar plugins/PluginTest-0.0.1-SNAPSHOT.jar_old 
 ```

## Start the application
 ``` bash
java -jar target/scylla-0.0.1-SNAPSHOT.jar

 ```
 ## Test the retailer stample headless
  bash tests/test_retailer.sh
