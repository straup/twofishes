package com.foursquare.twofishes

import com.mongodb.casbah.Imports._
import com.mongodb.casbah.MongoConnection
import com.novus.salat._
import com.novus.salat.annotations._
import com.novus.salat.dao._
import com.novus.salat.global._
import com.foursquare.twofishes.util.{Hacks, NameUtils}

import java.io._
import java.net.URI
import java.nio.ByteBuffer
import java.util.Arrays

import org.apache.hadoop.conf.Configuration 
import org.apache.hadoop.fs.{LocalFileSystem, Path}
import org.apache.hadoop.hbase.KeyValue.KeyComparator
import org.apache.hadoop.hbase.io.hfile.{CacheConfig, Compression, HFile, HFileScanner, HFileWriterV1, HFileWriterV2}
import org.apache.hadoop.hbase.util.Bytes._

import org.apache.thrift.TSerializer
import org.apache.thrift.protocol.TBinaryProtocol

import scala.collection.JavaConversions._
import scala.collection.mutable.HashMap
import scala.collection.mutable.HashSet
import scala.collection.mutable.ListBuffer

class OutputHFile(basepath: String, outputPrefixIndex: Boolean) {
  val blockSizeKey = "hbase.mapreduce.hfileoutputformat.blocksize"
  val compressionKey = "hfile.compression"

  val blockSize = HFile.DEFAULT_BLOCKSIZE
  val compressionAlgo = Compression.Algorithm.NONE.getName

  val conf = new Configuration()
  val cconf = new CacheConfig(conf)
  
  val maxPrefixLength = 5

  def hasFlag(record: NameIndex, flag: FeatureNameFlags) =
    (record.flags & flag.getValue) > 0

  def joinLists(lists: List[NameIndex]*): List[NameIndex] = {
    lists.toList.flatMap(l => {
      l.sortBy(_.pop * -1)
    })
  }

  def buildChildEntries(children: Iterator[GeocodeRecord]): Iterator[ChildEntry] = {
    for {
      child <- children
      val feature = child.toGeocodeServingFeature.feature
      name <- NameUtils.bestName(feature, Some("en"), false)
      slug <- child.slug
    } yield {
      var finalName = name.name
      if (child.cc == "US" && child._woeType == YahooWoeType.TOWN.getValue) {
        // awful hack to yank out state
        val stateCode = child.parents.find(p => p.startsWith("gadminid") && p.split("-").size == 2).map(_.split("-")(1))
        finalName = "%s, %s".format(name.name, stateCode)
      }
      new ChildEntry().setName(finalName).setSlug(slug)
    }
  }

  def buildChildMap(
    parentType: YahooWoeType,
    childType: YahooWoeType,
    limit: Int,
    minPopulation: Int,
    minPopulationPerCounty: Map[String, Int] = Map.empty,
    extraChildren: Map[String, List[ChildEntry]] = Map.empty
  ): Map[ObjectId, List[ChildEntry]] = {
    // find all parents of parentType
    val parents = MongoGeocodeDAO.find(MongoDBObject("_woetype" -> parentType.getValue))

    parents.map(parent => {
      val parentOid = parent._id
      val children = MongoGeocodeDAO.find(MongoDBObject("parents" -> parentOid.toString))
        .sort(orderBy = MongoDBObject("population" -> 1)) // sort by _id desc
        .limit(limit)

      val childEntries = buildChildEntries(children.filter(child => {
        val population = child.population.getOrElse(0)
        (population > minPopulation ||  minPopulationPerCounty.get(child.cc).exists(_ < population))
      })) ++ parent.ids.flatMap(id => extraChildren.getOrElse(id, Nil))

      (parentOid -> childEntries.toList)
    }).toMap

    // for each parent, find all children of childType, sorted by descending popularity
    // trim down those records super aggressively
  }

  def buildChildMaps() {
    // inject NYC boroughs here
    val nycEntries = buildChildEntries(
      MongoGeocodeDAO.find(MongoDBObject("ids" -> MongoDBObject("$in" -> Hacks.nycBoroughIds)))
    ).toList

    val extraChildrenMap = Map(("gadminid:US" -> nycEntries))

    val childMaps = 
      buildChildMap(YahooWoeType.COUNTRY, YahooWoeType.TOWN, 1000, 300000,
        Map(("US" -> 150000)), extraChildrenMap) ++
      buildChildMap(YahooWoeType.TOWN, YahooWoeType.SUBURB, 1000, 0) ++
      buildChildMap(YahooWoeType.ADMIN2, YahooWoeType.SUBURB, 1000, 0)

    val writer = buildV1Writer("child_map.hfile")

    println("sorting")

    val sortedMapKeys = childMaps.keys.toList.sort(objectIdSort)

    println("sorted")
    sortedMapKeys.foreach(k => {
      writer.append(k.toByteArray(),
        serializer.serialize(new ChildEntries().setEntries(childMaps(k))))
    })
    writer.close()
    println("done")

  }

  def sortRecordsByNames(records: List[NameIndex]) = {
    // val (pureNames, unpureNames) = records.partition(r => {
    //   !hasFlag(r, FeatureNameFlags.ALIAS)
    //   !hasFlag(r, FeatureNameFlags.DEACCENT)
    // })

    val (prefPureNames, nonPrefPureNames) = 
      records.partition(r =>
        (hasFlag(r, FeatureNameFlags.PREFERRED) || hasFlag(r, FeatureNameFlags.ALT_NAME)) &&
        (r.lang == "en" || hasFlag(r, FeatureNameFlags.LOCAL_LANG))
      )

    val (secondBestNames, worstNames) =
      nonPrefPureNames.partition(r => 
        r.lang == "en"
        || hasFlag(r, FeatureNameFlags.LOCAL_LANG)
      )

    (joinLists(prefPureNames), joinLists(secondBestNames, worstNames))
  }

  def getRecordsByPrefix(prefix: String, limit: Int) = {
    NameIndexDAO.find(
      MongoDBObject(
        "name" -> MongoDBObject("$regex" -> "^%s".format(prefix)))
    ).sort(orderBy = MongoDBObject("pop" -> -1)).limit(limit)
  }

  def buildV2Writer(filename: String) = {
    val fs = new LocalFileSystem() 
    val path = new Path(new File(basepath, filename).toString)
    fs.initialize(URI.create("file:///"), conf)
    new HFileWriterV2(conf, cconf, fs, path, blockSize, compressionAlgo, null)
  }

  def buildV1Writer(filename: String) = {
    val fs = new LocalFileSystem() 
    val path = new Path(new File(basepath, filename).toString)
    fs.initialize(URI.create("file:///"), conf)
    new HFileWriterV1(conf, cconf, fs, path, blockSize, compressionAlgo, null)
  }
  
  def writeCollection[T <: AnyRef, K <: Any](
    filename: String,
    callback: (T) => (Array[Byte], Array[Byte]),
    dao: SalatDAO[T, K],
    sortField: String
  ) {
    val writer = buildV2Writer(filename)
    var fidCount = 0
    val fidSize = dao.collection.count
    val fidCursor = dao.find(MongoDBObject())
      .sort(orderBy = MongoDBObject(sortField -> 1)) // sort by _id desc
    fidCursor.foreach(f => {
      val (k, v) = callback(f)
      writer.append(k, v)
      fidCount += 1
      if (fidCount % 1000 == 0) {
        println("processed %d of %d %s".format(fidCount, fidSize, filename))
      }
    })
    writer.close()
  }

  val comp = new ByteArrayComparator()
  def lexicalSort(a: String, b: String) = {
    comp.compare(a.getBytes(), b.getBytes()) < 0
  }
  def objectIdSort(a: ObjectId, b: ObjectId) = {
    comp.compare(a.toByteArray(), b.toByteArray()) < 0
  }

  def fidStringsToByteArray(fids: List[String]): Array[Byte] = {
    val oids = fids.flatMap(fid => fidMap.get(fid)).toSet
    val os = new ByteArrayOutputStream(12 * oids.size)
    oids.foreach(oid =>
      os.write(oid.toByteArray)
    )
    os.toByteArray()
  }

  def writeNames() {
    val nameMap = new HashMap[String, HashSet[String]]
    var nameCount = 0
    val nameSize = NameIndexDAO.collection.count
    val nameCursor = NameIndexDAO.find(MongoDBObject())
    var prefixSet = new HashSet[String]
    nameCursor.filterNot(_.name.isEmpty).foreach(n => {
      if (!nameMap.contains(n.name)) {
        nameMap(n.name) = new HashSet()
      }
      nameMap(n.name).add(n.fid)
      nameCount += 1
      if (nameCount % 100000 == 0) {
        println("processed %d of %d names".format(nameCount, nameSize))
      }

      if (outputPrefixIndex) {
        1.to(List(maxPrefixLength, n.name.size).min).foreach(length => 
          prefixSet.add(n.name.substring(0, length))
        )
      }
    })

    val writer = buildV2Writer("name_index.hfile")

    println("sorting")

    val sortedMap = nameMap.keys.toList.sort(lexicalSort)

    println("sorted")

    sortedMap.zipWithIndex.map({case (n, index) => {
      if (index % 100000 == 0) {
        println("outputted %d of %d entries to name_index".format(index, sortedMap.size))
      }
      val fids = nameMap(n).toList
      writer.append(n.getBytes(), fidStringsToByteArray(fids))
    }})
    writer.close()
    println("done")

    if (outputPrefixIndex) {
      doOutputPrefixIndex(prefixSet)
    }
  }

  def doOutputPrefixIndex(prefixSet: HashSet[String]) {
    println("sorting prefix set")
    val sortedPrefixes = prefixSet.toList.sort(lexicalSort)
    println("done sorting")

    val bestWoeTypes = List(
      YahooWoeType.POSTAL_CODE,
      YahooWoeType.TOWN,
      YahooWoeType.SUBURB,
      YahooWoeType.ADMIN3,
      YahooWoeType.AIRPORT,
      YahooWoeType.COUNTRY
    ).map(_.getValue)

    val prefixWriter = buildV2Writer("prefix_index.hfile")
    val numPrefixes = sortedPrefixes.size
    for {
      (prefix, index) <- sortedPrefixes.zipWithIndex
    } {
      if (index % 1000 == 0) {
        println("done with %d of %d prefixes".format(index, numPrefixes))
      }
      val records = getRecordsByPrefix(prefix, 1000)

      val (woeMatches, woeMismatches) = records.partition(r =>
        bestWoeTypes.contains(r.woeType))

      val (prefSortedRecords, unprefSortedRecords) =
        sortRecordsByNames(woeMatches.toList)

      var fids = new HashSet[String]
      prefSortedRecords.foreach(f => {
        if (fids.size < 50) {
          fids.add(f.fid)
        }
      })

      if (fids.size < 3) {
        unprefSortedRecords.foreach(f => {
          if (fids.size < 50) {
            fids.add(f.fid)
          }
        })
      }

      prefixWriter.append(prefix.getBytes(), fidStringsToByteArray(fids.toList))
    }

    prefixWriter.appendFileInfo("MAX_PREFIX_LENGTH".getBytes(), toBytes(maxPrefixLength))
    prefixWriter.close()
    println("done")
  }

  import java.io._

  type IdFixer = (String) => Option[String]

  val factory = new TBinaryProtocol.Factory()
  val serializer = new TSerializer(factory)

  def serializeBytes(g: GeocodeRecord, fixParentId: IdFixer) = {
    val f = g.toGeocodeServingFeature()
    val parents = for {
      parent <- f.scoringFeatures.parents
      parentId <- fixParentId(parent)
    } yield {
      parentId
    }

    f.scoringFeatures.setParents(parents)
    serializer.serialize(f)
  }

  class FidMap {
    val fidMap = new HashMap[String, Option[ObjectId]]

    def get(fid: String): Option[ObjectId] = {
      if (!fidMap.contains(fid)) {
        val oidOpt = MongoGeocodeDAO.primitiveProjection[ObjectId](
          MongoDBObject("ids" -> fid), "_id")
        fidMap(fid) = oidOpt
        if (oidOpt.isEmpty) {
          println("missing fid: %s".format(fid))
        }
      }

      fidMap.getOrElseUpdate(fid, None)
    }
  }

  val fidMap = new FidMap()

  def writeSlugsAndIds() {
    val p = new java.io.PrintWriter(new File(basepath, "id-mapping.txt"))
    MongoGeocodeDAO.find(MongoDBObject()).foreach(geocodeRecord => {
      (geocodeRecord.slug.toList ++ geocodeRecord.ids).foreach(id => {
        p.println("%s\t%s".format(id, geocodeRecord._id))
      })
    })
    p.close()
  }

  def process() {
    writeNames()
    writeSlugsAndIds()

    def fixParentId(fid: String) = fidMap.get(fid).map(_.toString)

    writeCollection("features.hfile",
      (g: GeocodeRecord) => 
        (g._id.toByteArray(), serializeBytes(g, fixParentId)),
      MongoGeocodeDAO, "_id")
  }

  val ThriftClassValueBytes: Array[Byte] = "value.thrift.class".getBytes("UTF-8")
  val ThriftClassKeyBytes: Array[Byte] = "key.thrift.class".getBytes("UTF-8")
  val ThriftEncodingKeyBytes: Array[Byte] = "thrift.protocol.factory.class".getBytes("UTF-8")

  def processForGeoId() {
    val geoCursor = MongoGeocodeDAO.find(MongoDBObject())

    def pickBestId(g: GeocodeRecord): String = {
      g.ids.find(_.startsWith("geonameid")).getOrElse(g.ids(0))
    }
    
    val gidMap = new HashMap[String, String]

    val ids = MongoGeocodeDAO.find(MongoDBObject()).map(pickBestId)
      .toList.sort(lexicalSort)

    geoCursor.foreach(g => {
      if (g.ids.size > 1) {
        val bestId = pickBestId(g)
        g.ids.foreach(id => {
          if (id != bestId) {
            gidMap(id) = bestId
          }
        })
      }
    })

    def fixParentId(fid: String) = Some(gidMap.getOrElse(fid, fid))

    val filename = "gid-features.hfile"
    val writer = buildV1Writer(filename)
    writer.appendFileInfo(ThriftClassValueBytes,
      classOf[GeocodeServingFeature].getName.getBytes("UTF-8"))
    writer.appendFileInfo(ThriftEncodingKeyBytes,
      factory.getClass.getName.getBytes("UTF-8"))

    var fidCount = 0
    val fidSize = ids.size
    ids.grouped(2000).foreach(chunk => {
      val records = MongoGeocodeDAO.find(MongoDBObject("ids" -> MongoDBObject("$in" -> chunk)))
      val idToRecord = records.map(r => (pickBestId(r), r)).toMap
      idToRecord.keys.toList.sort(lexicalSort).foreach(gid => {
        val g = idToRecord(gid)
        val (k, v) =
          (gid.getBytes("UTF-8"), serializeBytes(g, fixParentId))
        writer.append(k, v)
        fidCount += 1
        if (fidCount % 1000 == 0) {
          println("processed %d of %d %s".format(fidCount, fidSize, filename))
        }
      })
    })
    writer.close()
  }
}
