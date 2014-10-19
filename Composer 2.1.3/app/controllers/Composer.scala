package controllers

import play.api._
import play.api.mvc._
import play.api.data.{Form, Mapping}

import java.io.{File,FileInputStream,ByteArrayOutputStream}
import scala.io.Source
import com.itextpdf.text.pdf._
import com.itextpdf.text.{Paragraph, BaseColor, Document, Image, Element}

import models._
import controllers.Application.isAprilFool

object Composer extends Controller {
  lazy val pathfinderData = Application.pathfinderData
  lazy val dnd35Data = Application.dnd35Data
  lazy val testData = Application.testData

  def downloadPathfinder = downloadAction(pathfinderData)
  def downloadDnd35 = downloadAction(dnd35Data)
  def downloadTest = downloadAction(testData)

  def downloadAction(gameData: GameData) = Action(parse.multipartFormData) { request =>
    println("\n\nDownloading...")
    val iconic = request.body.file("iconic-custom-file").map{ filepart =>
      for (contentType <- filepart.contentType)
        println("File uploaded with content type: "+contentType)
      println("File named "+filepart.filename)
      println("File at "+filepart.ref.file.getAbsolutePath)
      filepart.ref.file
    }
    val bodydata = request.body.asFormUrlEncoded
    //val data: Map[String, String] = bodydata.flatMap { case (key, list) => key -> list.headOption } toMap
    val data: Map[String, String] = bodydata.mapValues { _.head }
    val sourceFolder = new File("public/pdf/"+gameData.game)

    val language = data.get("language").getOrElse("default")
    val sourceFolders = if (language != "default") {
      println("Language: "+language)
      val langFolder = new File("public/pdf/languages/"+language+"/"+gameData.game)
      langFolder :: sourceFolder :: Nil
    } else
      sourceFolder :: Nil
    println("Source folders: "+sourceFolders.map(_.getPath).mkString(", "))

    data.get("start-type") match {
      case Some("single") =>
        val character = CharacterData.parse(data, gameData, iconic)
        if (character.hasCustomIconic) println("Custom iconic found")

        val pdf = composePDF(character, gameData, sourceFolders)
        val filename = character.classes.toList.map(_.name).mkString(", ")+".pdf"

        Ok(pdf).as("application/pdf").withHeaders(
          "Content-disposition" -> ("attachment; filename=\""+filename+"\"")
        )

      case Some("party") =>
        val characters = CharacterData.parseParty(data, gameData)
        val pdf = composeParty(characters, gameData, sourceFolders)
        val filename = characters.map(_.classes.toList.map(_.name).mkString("-")).mkString(", ")+".pdf"

        Ok(pdf).as("application/pdf").withHeaders(
          "Content-disposition" -> ("attachment; filename=\""+filename+"\"")
        )

      case Some("gm") =>
        val gmdata = CharacterData.parseGM(data, gameData)
        val pdf = composeGM(gmdata, gameData, sourceFolders)
        Ok(pdf).as("application/pdf").withHeaders(
          "Content-disposition" -> ("attachment; filename=\""+(if(gameData.isDnd35) "Dungeon Master" else "Game Master")+".pdf\"")
        )

      case Some("all") =>
        val character = CharacterData.parse(data, gameData, iconic)
        val pdf = composeAll(character, gameData, sourceFolders)
        Ok(pdf).as("application/pdf").withHeaders(
          "Content-disposition" -> ("attachment; filename=\""+gameData.name+".pdf\"")
        )

      case _ => NotFound
    }
  }

  def composeGM(gmdata: GMData, gameData: GameData, folders: List[File]): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    val document = new Document
    val writer = PdfWriter.getInstance(document, out)
    writer.setRgbTransparencyBlending(true)
    document.open

    def placeGMPages(pages: List[Page]) {
      for (page <- pages; pageFile <- locatePage(folders, page)) {
        //val pageFile = new File(folder.getPath+"/"+page.file)
        val fis = new FileInputStream(pageFile)
        val reader = new PdfReader(fis)

        println("GM page: "+page.name)

        // get the right page size
        val template = writer.getImportedPage(reader, 1)
        val pageSize = reader.getPageSize(1)
        document.setPageSize(pageSize)
        document.newPage

        //  fill with white so the blend has something to work on
        val canvas = writer.getDirectContent
        val baseLayer = new PdfLayer("Character Sheet", writer);
        canvas.beginLayer(baseLayer)
        canvas.setColorFill(BaseColor.WHITE)
        canvas.rectangle(0f, 0f, 1000f, 1000f)
        canvas.fill

        canvas.setGState(defaultGstate)

        //  the page
        canvas.addTemplate(template, 0, 0)
        writeCopyright(canvas, writer, gameData)
        writeColourOverlay(canvas, gmdata.colour)
        canvas.endLayer()

        if (gmdata.aps.contains("kingmaker")) {
          if (page.slot == "kingdom" || page.slot == "hex-a4") {
            canvas.setGState(defaultGstate)
            val imgLayer = new PdfLayer("Logo image", writer)
            canvas.beginLayer(imgLayer)
            val imgFile = "public/images/logos/kingmaker.png"
            try {
              println("Adding logo: "+imgFile)
              val awtImage = java.awt.Toolkit.getDefaultToolkit().createImage(imgFile)
              val img = Image.getInstance(awtImage, null)
              img.scaleToFit(170f,50f)
              img.setAbsolutePosition(127f - (img.getScaledWidth() / 2), 800f - (img.getScaledHeight() / 2))
              canvas.addImage(img)
            } catch {
              case e: Exception => e.printStackTrace
            }
            canvas.endLayer()
          }
        }

        //  done
        fis.close
      }
    }

    if (gmdata.maps) {
      val maps = if (gmdata.maps3d) gameData.gm.maps.maps3d else gameData.gm.maps.maps2d
      placeGMPages(maps)
    }
    if (gmdata.gmCampaign)
      placeGMPages(gameData.gm.campaign)
    println("APs: "+gmdata.aps.mkString(", "))
    for (ap <- gameData.gm.aps) {
      if (gmdata.aps.contains(ap.code))
        placeGMPages(ap.pages)
    }
    
    document.close
    out.toByteArray
  }

  def composeParty(characters: List[CharacterData], gameData: GameData, folders: List[File]): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    val document = new Document
    val writer = PdfWriter.getInstance(document, out)
    writer.setRgbTransparencyBlending(true)
    document.open

    for (character <- characters) {
      println("START OF CHARACTER")
      addCharacterPages(character, gameData, folders, document, writer)
    }

    document.close
    out.toByteArray
  }

  def composePDF(character: CharacterData, gameData: GameData, folders: List[File]): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    val document = new Document
    val writer = PdfWriter.getInstance(document, out)
    writer.setRgbTransparencyBlending(true)
    document.open

    addCharacterPages(character, gameData, folders, document, writer)

    document.close
    out.toByteArray
  }

  def locatePage(folders: List[File], page: Page): Option[File] = locatePageFile(folders, page.file)

  def locatePageFile(folders: List[File], filename: String): Option[File] = {
    val availableFiles = folders.map(folder => new File(folder.getPath+"/"+filename)).filter(_.exists)
    println("Locate file: "+availableFiles.map(_.getPath).mkString(", "))
    availableFiles.headOption
  }

  def defaultGstate: PdfGState = {
    val defaultGstate = new PdfGState
    defaultGstate.setBlendMode(PdfGState.BM_NORMAL)
    defaultGstate.setFillOpacity(1.0f)
    defaultGstate
  }

  def addCharacterPages(character: CharacterData, gameData: GameData, folders: List[File], document: Document, writer: PdfWriter) {
    val iconic = if (isAprilFool) Some(IconicImage(IconicSet("1-paizo/3-advanced-races", "1 Paizo/3 Advanced Races"), "goblin-d20.png", "Goblin - d20"))
    else character.iconic

    val pages = new CharacterInterpretation(gameData, character).pages

    val colour = character.colour
    for (page <- pages; pageFile <- locatePage(folders, page)) {
      //val pageFile = new File(folder.getPath+"/"+page.file)
      val fis = new FileInputStream(pageFile)
      val reader = new PdfReader(fis)

      // get the right page size
      val template = writer.getImportedPage(reader, 1)
      val pageSize = reader.getPageSize(1)
      document.setPageSize(pageSize)
      document.newPage

      //  fill with white so the blend has something to work on
      val canvas = writer.getDirectContent
      val baseLayer = new PdfLayer("Character Sheet", writer);
      canvas.beginLayer(baseLayer)
      canvas.setColorFill(BaseColor.WHITE)
      canvas.rectangle(0f, 0f, 1500f, 1500f)
      canvas.fill


      //  the page
      canvas.addTemplate(template, 0, 0)

      //  copyright notice
      writeCopyright(canvas, writer, gameData)

      //  generic image
      if (!iconic.isDefined && !character.hasCustomIconic)
        writeIconic(canvas, writer, page.slot, "public/images/iconics/generic.png", character)

      // variant rules
      if (!character.variantRules.isEmpty) {
        if (character.variantRules.contains("wounds-vigour")) {
          overlayPage(canvas, writer, folders, "Pathfinder/Variant Rules/Wounds and Vigour.pdf")
        }
      }

      // april fool
      if (isAprilFool) {
        page.slot match {
          case "core" => overlayPage(canvas, writer, folders, "Extra/Special Overlays/Character Info.pdf")
          case "inventory" => overlayPage(canvas, writer, folders, "Extra/Special Overlays/Inventory.pdf")
        }
      }

      writeColourOverlay(canvas, colour)

      canvas.endLayer()

      //  logo
      if (page.slot == "core" || page.slot == "eidolon") {
        canvas.setGState(defaultGstate)
        val imgLayer = new PdfLayer("Logo image", writer)
        canvas.beginLayer(imgLayer)
        val imgFile = logoImage(gameData, character)
        try {
          println("Adding logo: "+imgFile)
          val awtImage = java.awt.Toolkit.getDefaultToolkit().createImage(imgFile)
          val img = Image.getInstance(awtImage, null)
          img.scaleToFit(170f,50f)
          img.setAbsolutePosition(127f - (img.getScaledWidth() / 2), 800f - (img.getScaledHeight() / 2))
          canvas.addImage(img)
        } catch {
          case e: Exception => e.printStackTrace
        }
        canvas.endLayer()
      }

      //  iconics
      if (character.hasCustomIconic)
        writeIconic(canvas, writer, page.slot, character.customIconic.get.getAbsolutePath, character)
      else if (iconic.isDefined)
        writeIconic(canvas, writer, page.slot, iconic.get.largeFile, character)

      //  watermark
      if (character.watermark != "") {
        writeWatermark(canvas, writer, character.watermark, colour)
      }

      fis.close
    }
  }

  def overlayPage(canvas: PdfContentByte, writer: PdfWriter, folders: List[File], fileName: String) {
    for (pageFile <- locatePageFile(folders, fileName)) {
      // val pageFile = new File(folder.getPath+"/"+fileName)
      val fis = new FileInputStream(pageFile)
      val reader = new PdfReader(fis)
      val template = writer.getImportedPage(reader, 1)

      canvas.setGState(defaultGstate)

      //  the page
      canvas.addTemplate(template, 0, 0)
    }
  }

  def writeCopyright(canvas: PdfContentByte, writer: PdfWriter, gameData: GameData) {
    val year = new org.joda.time.LocalDate().getYear()

    //  copyright notice
    canvas.setColorFill(new BaseColor(0.5f, 0.5f, 0.5f))
    val font = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.EMBEDDED)

    canvas.beginText
    val copyrightLayer = new PdfLayer("Iconic image", writer)
    canvas.beginLayer(copyrightLayer)
    canvas.setFontAndSize(font, 5)
    canvas.showTextAligned(Element.ALIGN_LEFT, "\u00A9 Marcus Downing "+year+"        http://charactersheets.minotaur.cc", 30, 22, 0)
    if (gameData.isPathfinder) {
      canvas.setFontAndSize(font, 4)

      canvas.showTextAligned(Element.ALIGN_LEFT, "This character sheet uses trademarks and/or copyrights owned by Paizo Publishing, LLC, which are used under Paizo's Community Use Policy. We are expressly prohibited from charging you to use or", 206, 22, 0)
      canvas.showTextAligned(Element.ALIGN_LEFT, "access this content. This character sheet is not published, endorsed, or specifically approved by Paizo Publishing. For more information about Paizo's Community Use Policy, please visit paizo.com/communityuse. For more information about Paizo Publishing and Paizo products, please visit paizo.com.", 30, 17, 0)
    } else if (gameData.isDnd35) {
      canvas.setFontAndSize(font, 4)

      canvas.showTextAligned(Element.ALIGN_LEFT, "This character sheet is not affiliated with, endorsed, sponsored, or specifically approved by Wizards of the Coast LLC. This character sheet may use the trademarks and other intellectual property of", 206, 22, 0)
      canvas.showTextAligned(Element.ALIGN_LEFT, "Wizards of the Coast LLC, which is permitted under Wizards' Fan Site Policy. For example, DUNGEONS & DRAGONS®, D&D®, PLAYER'S HANDBOOK 2®, and DUNGEON MASTER'S GUIDE® are trademark[s] of Wizards of the Coast and D&D® core rules, game mechanics, characters and their", 30, 17, 0)
      canvas.showTextAligned(Element.ALIGN_LEFT, "distinctive likenesses are the property of the Wizards of the Coast. For more information about Wizards of the Coast or any of Wizards' trademarks or other intellectual property, please visit their website.", 30, 12, 0)
    }
    canvas.endLayer
    canvas.endText
  }

  def writeIconic(canvas: PdfContentByte, writer: PdfWriter, slot: String, imgFilename: String, character: CharacterData) {
    slot match {
      case "background" | "inventory" =>
        println("Adding iconic image to "+slot)
        canvas.setGState(defaultGstate)
        val imgLayer = new PdfLayer("Iconic image", writer)
        canvas.beginLayer(imgLayer)
        try {
          println("Image: "+imgFilename)
          val awtImage = java.awt.Toolkit.getDefaultToolkit().createImage(imgFilename)
          val img = Image.getInstance(awtImage, null)
          img.scaleToFit(190f,220f)
          slot match {
            case "inventory" => img.setAbsolutePosition(315f - (img.getScaledWidth() / 2), 410f)
            case "background" => img.setAbsolutePosition(127f - (img.getScaledWidth() / 2), 425f)
            case _ =>
          }
          // img.setAbsolutePosition(315f - (img.getScaledWidth() / 2), 410f)
          canvas.addImage(img)
        } catch {
          case e: Exception => e.printStackTrace
        }
        canvas.endLayer()

      case "mini" =>
        println("Adding iconic image to "+slot)
        canvas.setGState(defaultGstate)
        val imgLayer = new PdfLayer("Iconic image", writer)
        canvas.beginLayer(imgLayer)
        try {
          println("Image: "+imgFilename)
          val awtImage = java.awt.Toolkit.getDefaultToolkit().createImage(imgFilename)
          val img = Image.getInstance(awtImage, null)

          // stat tracker
          img.scaleToFit(140f,150f)
          img.setRotationDegrees(180)
          img.setAbsolutePosition(122f - (img.getScaledWidth() / 2), 646f - (img.getScaledHeight() / 2))
          canvas.addImage(img)

          character.miniSize match {
            case "small" => 
              // stand-up figure
              img.scaleToFit(48f, 62f)
              img.setAbsolutePosition(335.6f - (img.getScaledWidth() / 2), 726 - (img.getScaledHeight() / 2))
              canvas.addImage(img)

              img.setRotationDegrees(0)
              img.setAbsolutePosition(335.6f - (img.getScaledWidth() / 2), 656f - (img.getScaledHeight() / 2))
              canvas.addImage(img)

              // square token
              img.scaleToFit(48f, 48f)
              img.setAbsolutePosition(514.5f - (img.getScaledWidth() / 2), 127f - (img.getScaledHeight() / 2))
              canvas.addImage(img)

              img.setRotationDegrees(180)
              img.setAbsolutePosition(514.5f - (img.getScaledWidth() / 2), 181f - (img.getScaledHeight() / 2))
              canvas.addImage(img)

            case "medium" =>
              // stand-up figure
              img.scaleToFit(66f, 89f)
              img.setAbsolutePosition(335.6f - (img.getScaledWidth() / 2), 714 - (img.getScaledHeight() / 2))
              canvas.addImage(img)

              img.setRotationDegrees(0)
              img.setAbsolutePosition(335.6f - (img.getScaledWidth() / 2), 620f - (img.getScaledHeight() / 2))
              canvas.addImage(img)

              // square token
              img.scaleToFit(66f, 66f)
              img.setAbsolutePosition(514.5f - (img.getScaledWidth() / 2), 126f - (img.getScaledHeight() / 2))
              canvas.addImage(img)

              img.setRotationDegrees(180)
              img.setAbsolutePosition(514.5f - (img.getScaledWidth() / 2), 198f - (img.getScaledHeight() / 2))
              canvas.addImage(img)

            case "large" =>
              // stand-up figure
              img.scaleToFit(135f, 180f)
              img.setAbsolutePosition(294f - (img.getScaledWidth() / 2), 632 - (img.getScaledHeight() / 2))
              canvas.addImage(img)

              img.setRotationDegrees(0)
              img.setAbsolutePosition(294f - (img.getScaledWidth() / 2), 445f - (img.getScaledHeight() / 2))
              canvas.addImage(img)

              // square token
              img.scaleToFit(135f, 135f)
              img.setAbsolutePosition(475f - (img.getScaledWidth() / 2), 220f - (img.getScaledHeight() / 2))
              canvas.addImage(img)

              img.setRotationDegrees(180)
              img.setAbsolutePosition(475f - (img.getScaledWidth() / 2), 364f - (img.getScaledHeight() / 2))
              canvas.addImage(img)
            case _ =>
          }
        } catch {
          case e: Exception => e.printStackTrace
        }
        canvas.endLayer()

      case _ => 
    }
  }

  def writeWatermark(canvas: PdfContentByte, writer: PdfWriter, watermark: String, colour: String) {
    println("Adding watermark: "+watermark)

    val watermarkGstate = new PdfGState
    watermarkGstate.setBlendMode(PdfGState.BM_NORMAL)
    watermarkGstate.setFillOpacity(0.3f)
    canvas.setGState(watermarkGstate)

    canvas.beginText
    val watermarkLayer = new PdfLayer("Watermark", writer)
    canvas.beginLayer(watermarkLayer)
    val font = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.EMBEDDED)
    canvas.setFontAndSize(font, (900f / watermark.length).toInt)
    //canvas.setColorFill(new BaseColor(0f, 0f, 0f))
    canvas.setColorFill(interpretColour(colour))
    canvas.showTextAligned(Element.ALIGN_CENTER, watermark, 365f, 400f, 60f)
    canvas.endLayer
    canvas.endText
  }

  def writeColourOverlay(canvas: PdfContentByte, colour: String) {
    if (colour == "black") {
      val gstate = new PdfGState
      
      gstate.setBlendMode(PdfGState.BM_OVERLAY)
      //gstate.setFillOpacity(0.5f)
      canvas.setGState(gstate)
      canvas.setColorFill(new BaseColor(0.1f, 0.1f, 0.1f))
      canvas.rectangle(0f, 0f, 1000f, 1000f)
      canvas.fill
      
      val gstate2 = new PdfGState
      gstate2.setBlendMode(PdfGState.BM_COLORDODGE)
      gstate2.setFillOpacity(0.5f)
      canvas.setGState(gstate2)
      canvas.setColorFill(new BaseColor(0.2f, 0.2f, 0.2f))
      canvas.rectangle(0f, 0f, 1500f, 1500f)
      canvas.fill
      
      //  ...correct hilights...
    } else if (colour != "normal") {
      val gstate = new PdfGState
      gstate.setBlendMode(colour match {
          case "light" => PdfGState.BM_SCREEN
          case "dark" => PdfGState.BM_OVERLAY
          case "black" => PdfGState.BM_COLORBURN
          case _ => new PdfName("Color")
      })
      canvas.setGState(gstate)
      canvas.setColorFill(interpretColour(colour))
      canvas.rectangle(0f, 0f, 1000f, 1000f)
      canvas.fill
    }
  }

  def interpretColour(colour: String): BaseColor = colour match {
    case "light" => new BaseColor(0.3f, 0.3f, 0.3f)
    case "dark" => new BaseColor(0.35f, 0.35f, 0.35f)
    case "black" => new BaseColor(0f, 0f, 0f)
    case "red" => new BaseColor(0.60f, 0.2f, 0.2f)
    case "orange" => new BaseColor(0.72f, 0.47f, 0.30f)
    case "yellow" => new BaseColor(1.0f, 0.92f, 0.55f)
    case "lime" => new BaseColor(0.77f, 0.85f, 0.55f)
    case "green" => new BaseColor(0.5f, 0.7f, 0.5f)
    case "cyan" => new BaseColor(0.6f, 0.75f, 0.75f)
    case "blue" => new BaseColor(0.55f, 0.63f, 0.80f)
    case "purple" => new BaseColor(0.80f, 0.6f, 0.70f)
    case "pink" => new BaseColor(1.0f, 0.60f, 0.65f)
    case _ => new BaseColor(0.3f, 0.3f, 0.3f)
  }

  def logoImage(gameData: GameData, character: CharacterData): String = {
    val fileName: String = character.logo.flatMap(_.fileName).getOrElse(
      gameData.game match {
        case "pathfinder" =>
          if (character.classes.exists(_.pages.exists(_.startsWith("core/neoexodus"))))
            "neoexodus.png"
          else
            "pathfinder.png"
        case "dnd35" => "dnd35.png"
        case _ => ""
      }
    )
    "public/images/logos/"+fileName
  }


  def composeAll(character: CharacterData, gameData: GameData, folders: List[File]): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    val document = new Document
    val writer = PdfWriter.getInstance(document, out)
    writer.setRgbTransparencyBlending(true)
    document.open

    println("Building all pages for: "+gameData.name)

    val colour = character.colour
    for (page <- gameData.pages; pageFile <- locatePage(folders, page)) {
      println("Adding page: "+page.name)
      //val pageFile = new File(folder.getPath+"/"+page.file)
      val fis = new FileInputStream(pageFile)
      val reader = new PdfReader(fis)

      // get the right page size
      val template = writer.getImportedPage(reader, 1)
      val pageSize = reader.getPageSize(1)
      document.setPageSize(pageSize)
      document.newPage

      val canvas = writer.getDirectContent
      val baseLayer = new PdfLayer("Character Sheet", writer);
      canvas.beginLayer(baseLayer)
      canvas.setColorFill(BaseColor.WHITE)
      canvas.rectangle(0f, 0f, 1000f, 1000f)
      canvas.fill

      //  the page
      canvas.addTemplate(template, 0, 0)

      //  copyright notice
      writeCopyright(canvas, writer, gameData)

      //  generic image
      writeIconic(canvas, writer, page.slot, "public/images/iconics/generic.png", character)

      writeColourOverlay(canvas, colour)

      canvas.endLayer()

      //  logo
      if (page.slot == "core" || page.slot == "eidolon") {
        canvas.setGState(defaultGstate)
        val imgLayer = new PdfLayer("Logo image", writer)
        canvas.beginLayer(imgLayer)
        val imgFile = logoImage(gameData, character)
        try {
          println("Adding logo: "+imgFile)
          val awtImage = java.awt.Toolkit.getDefaultToolkit().createImage(imgFile)
          val img = Image.getInstance(awtImage, null)
          img.scaleToFit(170f,50f)
          img.setAbsolutePosition(127f - (img.getScaledWidth() / 2), 800f - (img.getScaledHeight() / 2))
          canvas.addImage(img)
        } catch {
          case e: Exception => e.printStackTrace
        }
        canvas.endLayer()
      }

      //  watermark
      if (character.watermark != "") {
        writeWatermark(canvas, writer, character.watermark, colour)
      }

      fis.close
    }

    document.close
    out.toByteArray
  }
}

class CharacterInterpretation(gameData: GameData, character: CharacterData) {
  case class PageSlot(slot: String, variant: Option[String]) {
    lazy val page: Option[Page] = {
      val ps = gameData.pages.toList.filter { p => p.slot == slot && p.variant == variant }
      ps.headOption
    }
    override def toString = variant match {
      case Some(v) => slot+"/"+v 
      case None => slot
    }
  }

  def pageSlot(name: String) = 
    name.split("/", 2).toList match {
      case page :: Nil => PageSlot(page, None)
      case page :: variant :: _ => PageSlot(page, Some(variant))
      case _ => throw new Exception("Wow. I guess that match really wasn't exhaustive.")
    }

  def selectCharacterPages(classes: List[GameClass]): List[Page] = {
    //println(" -- Classes: "+classes.map(_.name).mkString(", "))
    val basePages = gameData.base.pages.toList.map(pageSlot)
    val classPages = classes.flatMap(_.pages).map(pageSlot)

    //  additional pages
    var pages = basePages ::: classPages
    if (character.includeCharacterBackground) {
      if (character.isPathfinderSociety)
        pages = pages ::: List(PageSlot("background", Some("pathfindersociety")))
      else
        pages = pages ::: List(PageSlot("background", None))
    }
    if (character.includeLycanthrope)
      pages = pages ::: List(PageSlot("lycanthrope", None))
    if (character.includePartyFunds)
      pages = pages ::: List(PageSlot("partyfunds", None))
    if (character.includeAnimalCompanion)
      pages = pages ::: List(PageSlot("animalcompanion", None))
    if (character.includeMini)
      pages = pages ::: List(PageSlot("mini", Some(character.miniSize)))

    println(" -- Base pages: "+basePages.map(_.toString).mkString(", "))
    println(" -- Class pages: "+classPages.map(_.toString).mkString(", "))
    var slotNames = pages.map(_.slot).distinct
    println(" -- Distinct slots: "+slotNames.mkString(", "))

    //  special cases
    if (character.hideInventory) {
      pages = PageSlot("core", Some("simple")) :: PageSlot("combat", Some("simple")) :: pages
      println("Slot names (before): "+slotNames.mkString(", "))
      slotNames = slotNames.filter(_ != "inventory")
      println("Slot names (simplified): "+slotNames.mkString(", "))
    } else if (character.moreClasses) {
      pages = PageSlot("core", Some("more")) :: pages
    }

    if (slotNames.contains("spellbook")) {
      val spellbookPage = character.spellbookSize match {
        case "small" => PageSlot("spellbook", Some("small"))
        case "medium" => PageSlot("spellbook", None)
        case "large" => PageSlot("spellbook", Some("large"))
      }
      pages = pages.filter(_.page != "spellbook") ::: (spellbookPage :: Nil)
    }
    if (character.inventoryStyle != "auto") {
      val page = PageSlot("inventory", Some(character.inventoryStyle))
      if (page.page != None)
        pages = pages.filter(_.page != "inventory") ::: (page :: Nil)
    }

    println(" -- Final slots: "+slotNames.mkString(", "))
    pages = for (slotName <- slotNames) yield {
      val pageInstances = pages.filter( _.slot == slotName)
      val overridingInstances = pageInstances.filter(v => v.variant != None)
      val selectedInstance =
        if (overridingInstances == Nil)
          pageInstances.head
        else
          overridingInstances.head

      println("Selecting page: "+slotName.toString)
      selectedInstance
    }
    
    println(" -- Selected "+pages.length+" pages: "+pages.map(_.toString).mkString(", "))
    val printPages = pages.toList.flatMap(_.page)
    println(" -- Printing "+printPages.length+" pages: "+printPages.map(_.name).mkString(", "))
    printPages
    //printPages.sortWith((a,b) => a.pagePosition < b.pagePosition)
  }

  def pages = {
    // var clsPages =
      if (character.partyDownload)
        character.classes.flatMap( cls => selectCharacterPages(List(cls)) )
      else
        selectCharacterPages(character.classes)

    // var pages = 
    //   if (character.includeGM) 
    //     clsPages ::: gameData.gm
    //   else
    //     clsPages

    // clsPages
  }
}
