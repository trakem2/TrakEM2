javaVersion=1.5
buildDir=build/

all <- T2-NIT.jar VectorString.jar TrakEM2_.jar

T2-NIT.jar <- lineage/LineageClassifier.java lineage/*.arff lineage/*.model lineage/*clj plugins.trakem2

VectorString.jar <- ini/trakem2/vector/*java

TrakEM2_.jar <- ini/trakem2/*java ini/trakem2/analysis/*java ini/trakem2/display/*.java ini/trakem2/display/graphics/*java ini/trakem2/imaging/*.java ini/trakem2/io/*java ini/trakem2/persistence/*java ini/trakem2/plugin/*java ini/trakem2/scripting/*java ini/trakem2/tree/*java ini/trakem2/utils/*java mpi/*/*/*.java mpi/*/*/*/*.java mpicbg/*/*/*.java lenscorrection/*.java img/*.png plugins.config bunwarpj/trakem2/transform/*.java lineage/Identify.java

