package HOME

import java.awt.Color

import HOME.MyClass._
import javax.swing.border.{LineBorder, TitledBorder}
import javax.swing.{Box, ImageIcon}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.language.postfixOps
import scala.swing.Dialog.{Message, Result}
import scala.swing._
import scala.swing.event.{ButtonClicked, MouseClicked, SelectionChanged, ValueChanged}
import scala.util.Success

/** Basic room components: devices and room name
 * used by [[GUIRoom]]
 */
sealed trait Room {
  var devices : Set[GUIDevice]
  def name : String
}

/** Provides an interface for communicating updates to [[Coordinator]]
 * every feature that can be updated by user needs to implement this trait,
 * see [[BinaryFeature]],[[SliderFeature]],[[ListFeature]]
 *
 */
sealed trait EditableFeature{
  /** sends device's properties update to coordinator
   *
   * @param devName device updating its feature
   * @param cmdMsg [[Msg]] type of update
   * @param newValue new feature value
   * @return future stating wheter the connected device updated its value
   *
   * Such return promise will be completed only when the physically connected device updates
   * it's feature value and sends a confirm back to [[Coordinator]].
   * Update confirmation leads to feature update in GUI.
   */
  def update(devName : String,cmdMsg :String,newValue:String): Future[Unit] = {
    val p = Promise[Unit]
    Coordinator.sendUpdate(devName, cmdMsg, newValue).onComplete {
      case Success(_) => setVal(newValue); p.success(() => Unit);
      case _ => Dialog.showMessage(title ="Update Error",message = "Something wrong happened while trying to update a device",messageType = Dialog.Message.Error);  p.failure(_)
    }
    p.future
  }

  /**  feature value getter
   *
   * @return feature value
   */
  def getVal : String
  /** feature value setter */
  def setVal(v:String) : Unit
}

/** Graphical representation of a
 *
 * @param name room name
 * @param devices devices in the room
 */
class GUIRoom(override val name:String, override var devices:Set[GUIDevice]) extends ScrollPane with Room {
  val devicePanel = new BoxPanel(Orientation.Vertical)
  val adDeviceBtn: Button =
    new Button("Add device") {
      reactions += {
        case ButtonClicked(_) => DeviceDialog()
      }
    }
    val bp: BorderPanel = new BorderPanel {
      add(new FlowPanel() {
          border = new TitledBorder("Sensors")
          contents ++= devices.filter( dev => Device.isSensor(dev.d))
        },BorderPanel.Position.North)

      add(devicePanel, BorderPanel.Position.Center)
      add(adDeviceBtn, BorderPanel.Position.South)
    }
    contents = bp
    for (i <- devices.filter( dev => !Device.isSensor(dev.d))) addDevicePane(i)

  /** Adds a GUIDevice to room
   *
   * @param dev GUIDevice to add to room
   * utility function used internally
   */
  private def addDevicePane(dev : GUIDevice): Unit ={
    devicePanel.peer.add(Box.createVerticalStrut(Constants.GUIDeviceGAP))
    devicePanel.contents += dev
  }

  /** adds a new device to room
   *
   * @param dev device to be added
   */
  def addDevice(dev:Device): Unit ={
    dev.turnOn()
    val tmp = PrintDevicePane(dev)
    addDevicePane(tmp)
    devices+=tmp
  }

  /** removes a device from room
   *
   * @param dev device to be removed
   */
  def removeDevice(dev:Device):Unit={
    devices -= devices.find(_.name == dev.name).get
  }
}
/**Factory for [[GUIRoom]]*/
object GUIRoom{
  /**
   *
   * @param roomName room name
   * @param devices devices in room
   * @return a new istance of [[GUIRoom]]
   *
   * provides abstraction between devices and [[GUIDevice]]
   */
  def apply(roomName: String,devices:Set[Device]): GUIRoom = new GUIRoom(roomName,devices.map(PrintDevicePane(_)))
}

/** Singleton GUI for HOME system
 *
 * GUI is made by a [[TabbedPane]] where each page is a [[GUIRoom]]
 */
object GUI extends MainFrame {
  var rooms: Set[GUIRoom] = Set.empty
  for(i <- Rooms.allRooms) {
    rooms += GUIRoom(i, Coordinator.getDevices.filter(_.room equals i))
  }
  protected val tp: TabbedPane = new TabbedPane {
    //Initializing basic rooms
    pages+= new TabbedPane.Page("Home", HomePage())
    for(i <- rooms) pages += new TabbedPane.Page(i.name,i)
    pages+= new TabbedPane.Page(Constants.AddPane,new BorderPanel())
  }

  /** GUI's version of a main method.
   *
   * @return system's GUI
   */
  def top: MainFrame = new MainFrame {
    title = "HOME"
    reactions += {
      /**
       * Whenever the last [[TabbedPane.Page]] is clicked the procedure
       * for instantiating a new room is started.
       */
      case SelectionChanged(_) =>
        for {
          last <- getLastIndex
          name <- getRoomName
        } yield {
          val devices = Constants.devicesPerRoom(name)
          val newRoom = GUIRoom(name,devices)
          RegisterDevice(devices.map(_.asInstanceOf[AssociableDevice]))
          val newRoomPane = new TabbedPane.Page(name, newRoom)
          Rooms.addRoom(name)
          rooms += newRoom
          tp.selection.page = newRoomPane
          tp.pages.insert(last.index, newRoomPane)
        }
    }
    //used to set items in the main window inside a vertical BoxPanel
    contents = tp
    listenTo(tp.selection)
    size = WindowSize(WindowSizeType.MainW)

    /** Index of clicked page in [[TabbedPane]]
     *
     * @return page clicked
     */
    private def getLastIndex: Option[TabbedPane.Page] = {
        tp.selection.page.title match {
          case Constants.AddPane => tp.pages.find(page => page.title equals Constants.AddPane)
          case _ => None
        }
    }

    /** Dialog where user can choose new room's name
     *
     * @return  [[Some]] new room name if user input is valid, [[None]] otherwise
     */
    private def getRoomName: Option[String] = {
      import Dialog._
      val name = showInput(tp,
        "Room name:",
        "Add room",
        Message.Plain,
        Swing.EmptyIcon,
        Nil, "")
      //TODO: THINK OF A MORE FUNCTIONAL WAY TO IMPLEMENT INPUT CHECK
      if (name.isDefined && name.get.trim.length > 0 && !name.get.equals(Constants.AddPane) && !tp.pages.exists(page => page.title equals name.get)) {
        Rooms.addRoom(name.get)
        name
      } else {
        if (name.isDefined) {
          showMessage(tp, "Room already existing or incorrect room name", Message.Error toString)
        }
        None
      }
    }
  }

  /** Room currently open in GUI
   *
   * @return room name
   */
  def getCurrentRoom: String = {
    tp.selection.page.title
  }

  /** Remove a device from GUI
   *
   * @param device device to be removed
   */
  def removeDevice(device:Device) : Unit = {
    rooms.find(_.devices.map(_.name) contains device.name).get.removeDevice(device)
  }

  /** Updates a device's feature
   *
   * @param d device to update
   * @param cmdMsg [[Msg]] type of update
   * @param newVal new feature value
   *
   * Called whenever a profile makes a change to a device feature and needs to reflect such change to GUI devices
   */
  def updateDevice(d: Device,cmdMsg:String,newVal:String):Unit ={
    rooms.find(_.devices.map(_.name).contains(d.name)).get.devices.find(_.name == d.name).get.updateDevice(cmdMsg,newVal)
  }

  /** Shutdown application */
  override def closeOperation(): Unit = {
    super.closeOperation()
    Application.closeApplication()
  }
}

/** Dialog through which users can add devices to a room
 *
 */
class AddDeviceDialog extends Dialog {
  private val dimension = WindowSize(WindowSizeType.DialogInput)
  //Can't add sensors
  private val deviceType = new ComboBox[DeviceType](DeviceType.listTypes -- Seq(MotionSensorType,ThermometerType,HygrometerType,PhotometerType) toSeq)

  preferredSize = dimension
  title = "Add device"
  contents = new BoxPanel(Orientation.Vertical) {
    private val labels = new FlowPanel() {
      contents ++= Seq(new Label("Device name: "),new Label("Device type: "),deviceType)
    }
    private val buttons = new FlowPanel() {
      contents ++= Seq(
        new Button("Create"){
          reactions += {
            case ButtonClicked(_) =>
              val room = GUI.getCurrentRoom
              val dev = Device(deviceType.selection.item.toString,DeviceIDGenerator(),room).get.asInstanceOf[AssociableDevice]
              RegisterDevice(dev).onComplete {
                    case Success(_) => GUI.rooms.find(_.name equals room).get.addDevice(dev);repaint(); close()
                    case _ => Dialog.showMessage(message = "Couldn't add device, try again", messageType = Dialog.Message.Error)
                }
          }
        },
        new Button("Cancel") {
          reactions += {
            case ButtonClicked(_) =>
              close()
          }
        })
    }
    contents++= Seq(labels,buttons)
  }
  open()
}

/** Factory for [[AddDeviceDialog]]
 *
 */
object DeviceDialog {
  def apply(): AddDeviceDialog = {
    new AddDeviceDialog()
  }
}

class ChangeOrDeleteProfileDialog(delete: String, labelProfile: Label) extends Dialog {
  private val dimension = WindowSize(WindowSizeType.AddProfile)
  private val profiles = new ComboBox[String](Profile.getProfileNames toSeq)
  preferredSize = dimension
  modal = true
  private val dialog = new BoxPanel(Orientation.Vertical) {
    contents += new BoxPanel(Orientation.Horizontal) {
      contents += new FlowPanel() {
        contents ++= Seq(new Label("Profiles: "), profiles)
      }
    }
    contents += applyDialog
  }
  contents = dialog
  open()

  def applyDialog: Button = {
    delete match {
      case "Change profile" =>
        this.title = "Change Profile"
        new Button("Confirm") {
          reactions += {
            case ButtonClicked(_) => changeProfile(delete)
          }
        }
      case "Delete profile" =>
        this.title = "Delete Profile"
        new Button("Delete") {
          reactions += {
            case ButtonClicked(_) => changeProfile(delete)
          }
        }
    }
  }

  def changeProfile(name: String): Unit = {
    val selectedProfile = profiles.selection.item
    name match {
      case "Change profile" =>
        labelProfile.text = "Current active profile: " + selectedProfile
        Coordinator.setProfile(Profile(selectedProfile))
      case _ => Profile.removeProfile(selectedProfile)
    }
    close()
  }
}
object ChangeOrDeleteProfile {
  def apply(delete: String, labelProfile: Label): ChangeOrDeleteProfileDialog = {
    new ChangeOrDeleteProfileDialog(delete, labelProfile)
  }
}

class CreateProfileDialog extends Dialog {
  private val profileName = new TextField(10)
  private val description = new TextField(10)
  var onActivationCommands: Set[(Device, CommandMsg)] = Set.empty
  var sensorRules: List[(String, Double, String, Device)] = List.empty
  var thermometerNotificationCommands: List[(List[(String, Double, String, Device)], Set[(Device, CommandMsg)])] = List.empty
  var hygrometerNotificationCommands: List[(List[(String, Double, String, Device)], Set[(Device, CommandMsg)])] = List.empty
  var photometerNotificationCommands: List[(List[(String, Double, String, Device)], Set[(Device, CommandMsg)])] = List.empty
  var motionSensorNotificationCommands: List[(List[(String, String, Device)], Set[(Device, CommandMsg)])] = List.empty
  //private val programmedStuffCommands: Set[(Device, CommandMsg)] = Set.empty
  title = "New Profile"
  modal = true

  private val newProfileDialog = this

  contents = new GridPanel(6,2) {
    contents += new FlowPanel() {
      contents ++= Seq(new Label("Insert a profile name: "), profileName)
    }
    contents += new FlowPanel() {
      contents ++= Seq(new Label("Insert a description: "), description)
    }
    contents += new FlowPanel() {
      contents ++= Seq(new Label("On activation: "),
        new Button("Add rules") {
        reactions += {case ButtonClicked(_) => AllDevice(Rooms.allRooms, newProfileDialog, null)}}
      )
    }
    contents += new FlowPanel() {
      contents ++= Seq(new Label("On sensor changed: "), new Button("Add rules") {
        reactions += { case ButtonClicked(_) => SensorReaction(newProfileDialog)}}
      )
    }
    /* contents += new FlowPanel() {
       contents += new Label("Programmed Stuff: ")
       contents += new Button("Devices") {
         reactions += {
           case ButtonClicked(_) => AllDevice(Rooms.allRooms, isRoutine = true, programmedStuffCommands)
         }
       }
     }*/
    contents += new FlowPanel() {
      contents += new Button("Confirm") {
        reactions += {
          case ButtonClicked(_) =>
            AlertMessage.alertIsCorrectName(profileName.text.map(_.toUpper)) match {
              case false =>
              case _ =>
                val generatedOnActivationCommand: Set[Device => Unit] = CustomProfileBuilder.generateCommandSet(onActivationCommands)
                var generatedThermometerSensorCommandsMap: Map[(String, Double) => Boolean, Set[Device => Unit]] = Map.empty
                var generatedHygrometerSensorCommandsMap: Map[(String, Double) => Boolean, Set[Device => Unit]] = Map.empty
                var generatedPhotometerSensorCommandsMap: Map[(String, Double) => Boolean, Set[Device => Unit]] = Map.empty
                var generatedMotionSensorCommandsMap: Map[String, Set[Device => Unit]] = Map.empty

                //for each Sensor with attached commands
                for (rules <- sensorRules.groupBy(_._4)) {
                  if (rules._1.deviceType == MotionSensorType) {
                    //get all the commands associated to this Sensor
                    val motionSensorCommands = (motionSensorNotificationCommands.filter(_._1.head._3 == rules._1).flatMap(_._2)).toSet
                    val generatedMotionSensorCommands = CustomProfileBuilder.generateCommandSet(motionSensorCommands)
                    generatedMotionSensorCommandsMap = generatedMotionSensorCommandsMap + (rules._1.room -> generatedMotionSensorCommands)
                  } else {
                    val tuple = rules._2.head
                    val checkFunction = CustomProfileBuilder.generateCheckFunction(tuple._1, tuple._2, tuple._3)
                    if(thermometerNotificationCommands.nonEmpty) {
                      val thermometerCommands = (thermometerNotificationCommands.filter(_._1.head._4 == rules._1).flatMap(_._2)).toSet
                      val generatedThermometerCommands = CustomProfileBuilder.generateCommandSet(thermometerCommands)
                      generatedThermometerSensorCommandsMap = generatedThermometerSensorCommandsMap + (checkFunction -> generatedThermometerCommands)
                    }
                    if(hygrometerNotificationCommands.nonEmpty) {
                      val hygrometerCommands = (hygrometerNotificationCommands.filter(_._1.head._4 == rules._1).flatMap(_._2)).toSet
                      val generatedHygrometerCommands = CustomProfileBuilder.generateCommandSet(hygrometerCommands)
                      generatedHygrometerSensorCommandsMap = generatedHygrometerSensorCommandsMap + (checkFunction -> generatedHygrometerCommands)
                    }
                    if(photometerNotificationCommands.nonEmpty) {
                      val photometerCommands = (photometerNotificationCommands.filter(_._1.head._4 == rules._1).flatMap(_._2)).toSet
                      val generatedPhotometerCommands = CustomProfileBuilder.generateCommandSet(photometerCommands)
                      generatedPhotometerSensorCommandsMap = generatedPhotometerSensorCommandsMap + (checkFunction -> generatedPhotometerCommands)
                    }
                  }
                }
                println(generatedMotionSensorCommandsMap)
                println(generatedThermometerSensorCommandsMap)
                println(generatedHygrometerSensorCommandsMap)
                println(generatedPhotometerSensorCommandsMap)
                val newProfile = CustomProfileBuilder.generateFromParams(profileName.text.map(_.toUpper), description.text, generatedOnActivationCommand, generatedThermometerSensorCommandsMap,
                  generatedHygrometerSensorCommandsMap, generatedPhotometerSensorCommandsMap, generatedMotionSensorCommandsMap, DummyUtils.dummySet, {})
                Profile.addProfile(newProfile)
                close()
            }
        }
      }
      contents += new Button("Cancel") {
        reactions += {
          case ButtonClicked(_) => close()
        }
      }
    }
  }
  open()
}
object CreateProfile {
  def apply(): CreateProfileDialog = {
    new CreateProfileDialog()
  }
}

class SensorReactionDialog(dialog: CreateProfileDialog) extends Dialog {
  modal = true
  title = "Sensor Reaction"
  location = new Point(300,0)
  preferredSize = new Dimension(900, 400)
  contents = new ScrollPane() {
    contents = applyTemplate
  }

  var key: List[(String, Double, String, Device)] = List.empty
  val emptySet: Set[(Device, CommandMsg)] = Set.empty

  def applyTemplate : BoxPanel = {
    val panel = new BoxPanel(Orientation.Vertical)
    for(i <- Coordinator.getDevices) {
      val devicePanel = new BoxPanel(Orientation.Horizontal)
      devicePanel.peer.add(Box.createVerticalStrut(10))
      devicePanel.border = new LineBorder(Color.BLACK, 2)
      if(Device.isSensor(i)) {
        val comboRooms: StringComboBox = StringComboBox(Rooms.allRooms toSeq)
        val value = new TextField(10)
        devicePanel.contents += new FlowPanel() {
          contents += new Label(i.name + ": ")
          contents += applyComponent(i, this)
          if(i.deviceType != MotionSensorType) {
            contents += value
          }
          contents += new Label("Select rooms where yuo want to apply rules")
          contents += comboRooms
          contents += new Button("Do") {
            reactions += {
              case ButtonClicked(_) =>
                for(sym <- devicePanel.contents(1).asInstanceOf[FlowPanel].contents) yield {
                  sym match {
                    case x: ComboBox[_] if !x.equals(comboRooms) =>
                      i.deviceType match {
                        case MotionSensorType => key = List((giveSymbol(sym), Double.NaN, comboRooms.selection.item, i))
                        case _ =>AlertMessage.alertIsCorrectValue(value.text) match {
                          case true => key = List((giveSymbol(sym), value.text.toDouble, comboRooms.selection.item, i))
                          case _ => key = List.empty
                        }
                      }
                    case _ =>
                  }
                }
                key.isEmpty match {
                  case true =>
                  case _ => dialog.sensorRules ++= key
                    println(dialog.sensorRules)
                    roomsDevices(comboRooms.selection.item)
                }
            }
          }
        }
        panel.contents += devicePanel
      }
    }
    panel.contents += new Button("Confirm") {
      reactions += {
        case ButtonClicked(_) => close()
      }
    }
    panel
  }

  def applyComponent(dev: Device, panel: FlowPanel) : Component = dev.deviceType match {
    case MotionSensorType => panel.contents+=new Label("Motion ")
      StringComboBox(Set("Detecting") toSeq)
    case HygrometerType =>  panel.contents+=new Label("Humidity ")
      StringComboBox(Set("=", ">=", "<=", ">", "<") toSeq)
    case PhotometerType => panel.contents+=new Label("Intensity ")
      StringComboBox(Set("=", ">=", "<=", ">", "<") toSeq)
    case ThermometerType => panel.contents+=new Label("Temperature ")
      StringComboBox(Set("=", ">=", "<=", ">", "<") toSeq)
    case _ => this.errUnexpected(UnexpectedDeviceType, dev.deviceType.toString)
  }

  def giveSymbol(x: Any): String = x match {
    case p: StringComboBox => p.selection.item
    case _ => ""
  }

  def roomsDevices(room: String) : Dialog = {
    AllDevice(Set(room), dialog, key)
  }
  open()
}
object SensorReaction {
  def apply(dialog: CreateProfileDialog): SensorReactionDialog = {
    new SensorReactionDialog(dialog)
  }
}

/*class AddProgrammedStuffDialog extends Dialog {
  title = "Add Programmed Stuff"
  location = new Point(0,250)

  contents = new ScrollPane() {
    contents = applyTemplate
  }

  def applyTemplate : BoxPanel = {
    val panel = new BoxPanel(Orientation.Vertical)
    for(i <- Coordinator.devices) {
      val devPanel = new BoxPanel(Orientation.Horizontal) {
        contents += new FlowPanel() {
          contents += new Label(i.name+""+i.room)
          contents += new BoxPanel(Orientation.Vertical) {
            contents += new TextField(8)
            contents += new TextField(8)
          }
          contents += new Button("Add")
        }
      }
      panel.contents += devPanel
    }
    panel
  }
  open()
}

object AddProgrammedStuff {
  def apply(): AddProgrammedStuffDialog = {
    new AddProgrammedStuffDialog()
  }
}*/

class AllDeviceDialog(rooms: Set[String], dialog: CreateProfileDialog, sensorRule: List[(String, Double, String, Device)]) extends Dialog {
  modal = true
  title = "All Devices"
  location = new Point(300,250)
  preferredSize = new Dimension(1000,500)

  contents = new ScrollPane() {
    contents = applyTemplate
  }

  def applyTemplate : BoxPanel = {
    val panel = new BoxPanel(Orientation.Vertical)
    for(i <- Coordinator.getDevices) {
      val devPanel = new BoxPanel(Orientation.Horizontal)
      if(!Device.isSensor(i) && rooms.contains(i.room)) {
        val applyButton = new Button("Add")
        devPanel.peer.add(Box.createVerticalStrut(10))
        devPanel.border = new LineBorder(Color.BLACK, 2)
        devPanel.contents += new FlowPanel() {
          contents += new Label(i.name)
          MapDeviceCommands.apply(i)
          for(a <- MapDeviceCommands.getCommands) {
            val component = applyComponent(a,i,this)
            applyButton.reactions += {
              case ButtonClicked(_) => addRule(component, i, switchStatus(a))
            }
          }
          /*if(isRoutine) {
            contents += new Label("Start at: ")
            contents += new TextField(8)
            contents += new Label("End at: ")
            contents += new TextField(8)
          }*/
          contents += applyButton
        }
        panel.contents += devPanel
      }
    }

    panel.contents += new FlowPanel() {
      contents += new Button("Confirm") {
        reactions += {
          case ButtonClicked(_) => close()
        }
      }
    }
    panel
  }

  def applyComponent(command: String, device: Device, panel: FlowPanel): Component = command match {
    case Msg.washingType => device.deviceType match {
      case WashingMachineType =>
        val component = StringComboBox(Seq(WashingType.MIX, WashingType.RAPID, WashingType.WOOL)map(_.toString))
        panel.contents ++= Seq(new Label("Washing type: "), component)
        component
      case _ => null
    }
    case Msg.setProgram => device.deviceType match {
      case DishWasherType =>
        val component = StringComboBox(Seq(DishWasherProgram.DIRTY, DishWasherProgram.FAST, DishWasherProgram.FRAGILE)map(_.toString))
        panel.contents ++= Seq(new Label("Program type: "), component)
        component
      case _ => null
    }
    case Msg.RPM => device.deviceType match {
      case WashingMachineType | DishWasherType =>
        val component = StringComboBox(Seq(RPM.SLOW, RPM.MEDIUM, RPM.FAST)map(_.toString))
        panel.contents ++= Seq(new Label("RPM: "), component)
        component
      case _ => null
    }
    case Msg.addExtra => device.deviceType match {
      case WashingMachineType =>
        val component = StringComboBox(Seq(WashingMachineExtra.SpecialColors, WashingMachineExtra.SuperDirty, WashingMachineExtra.SuperDry)map(_.toString))
        panel.contents ++= Seq(new Label("Extras: "), component)
        component
      case DishWasherType =>
        val component = StringComboBox(Seq(DishWasherExtra.SuperDirty, DishWasherExtra.SuperHygiene, DishWasherExtra.SuperSteam)map(_.toString))
        panel.contents ++= Seq(new Label("Extras: "), component)
        component
      case _ => null
    }
    case Msg.setMode => device.deviceType match {
      case OvenType =>
        val component = StringComboBox(Seq(OvenMode.CONVENTIONAL, OvenMode.DEFROSTING, OvenMode.GRILL, OvenMode.LOWER,
          OvenMode.UPPER, OvenMode.VENTILATED)map(_.toString))
        panel.contents ++= Seq(new Label("Working mode: "), component)
        component
      case _ => null
    }
    case Msg.close | Msg.mute | Msg.off =>
      val component = new ToggleButton(command) {
        reactions += {
          case ButtonClicked(_) => this.text=switchStatus(this.text)
        }
      }
      panel.contents ++= Seq(component)
      component
    case _ =>
      val component = new TextField(10)
      panel.contents ++= Seq(new Label(command), component)
      component
  }

  def switchStatus(status: String) : String = status match {
    case "on" => "off"
    case "off" => "on"
    case "close" => "open"
    case "open" => "close"
    case _ => status
  }

  def addRule(component: Component, device: Device, command: String) : Unit = command match {
    case Msg.on | Msg.off | Msg.open | Msg.close | Msg.mute => sensorRule match {
      case null => dialog.onActivationCommands ++= Set((device, CommandMsg(Msg.nullCommandId, command, getComponentInfo(component, command))))
      case _ => sensorRule.head._4.deviceType match {
        case ThermometerType => dialog.thermometerNotificationCommands ++= List((sensorRule, Set((device, CommandMsg(Msg.nullCommandId, command, getComponentInfo(component, command))))))
          println(dialog.thermometerNotificationCommands)
        case PhotometerType => dialog.photometerNotificationCommands ++= List((sensorRule, Set((device, CommandMsg(Msg.nullCommandId, command, getComponentInfo(component, command))))))
          println(dialog.photometerNotificationCommands)
        case HygrometerType => dialog.hygrometerNotificationCommands ++= List((sensorRule, Set((device, CommandMsg(Msg.nullCommandId, command, getComponentInfo(component, command))))))
          println(dialog.hygrometerNotificationCommands)
        case MotionSensorType => dialog.motionSensorNotificationCommands ++= List((List((sensorRule.head._1, sensorRule.head._3, sensorRule.head._4)),
          Set((device, CommandMsg(Msg.nullCommandId, command, getComponentInfo(component, command))))))
          println(dialog.motionSensorNotificationCommands)
        case _ =>
      }
    }
    case _ => sensorRule match {
      case null => dialog.onActivationCommands ++= Set((device, CommandMsg(Msg.nullCommandId, command, getComponentInfo(component, command))))
      case _ => sensorRule.head._4.deviceType match {
        case ThermometerType => dialog.thermometerNotificationCommands ++= List((sensorRule, Set((device, CommandMsg(Msg.nullCommandId, command, getComponentInfo(component, command))))))
        case PhotometerType => dialog.photometerNotificationCommands ++= List((sensorRule, Set((device, CommandMsg(Msg.nullCommandId, command, getComponentInfo(component, command))))))
        case HygrometerType => dialog.hygrometerNotificationCommands ++= List((sensorRule, Set((device, CommandMsg(Msg.nullCommandId, command, getComponentInfo(component, command))))))
        case MotionSensorType => dialog.motionSensorNotificationCommands ++= List((List((sensorRule.head._1, sensorRule.head._3, sensorRule.head._4)),
          Set((device, CommandMsg(Msg.nullCommandId, command, getComponentInfo(component, command))))))
        case _ =>
      }
    }
  }

  def getComponentInfo(x: Any, command: String): String = x match {
    case p: TextField => command match {
      case Msg.setIntensity | Msg.setTemperature | Msg.setHumidity | Msg.setVolume => p.text
        /*AlertMessage.alertIsCorrectValue(p.text) match {
        case true => p.text
        case _ => p.text = Dialog.showInput(null,
          "Insert a value: ",
          "Insert a correct value",
          Message.Plain,
          Swing.EmptyIcon,
          Nil, "").get
          p.text
      }*/
      case _ => ""
    }
    case p: ToggleButton => command match {
      case Msg.on | Msg.off | Msg.close | Msg.open | Msg.mute => p.text
      case _ => ""
    }
    case p: StringComboBox => command match {
      case Msg.washingType | Msg.RPM | Msg.addExtra | Msg.setMode | Msg.setProgram => p.selection.item
      case _ => ""
    }
    case _ => ""
  }

  open()
}

object AllDevice {
  def apply(rooms: Set[String], dialog: CreateProfileDialog, sensorRule: List[(String, Double, String, Device)]): AllDeviceDialog = {
    new AllDeviceDialog(rooms, dialog, sensorRule)
  }
}

class HomePageLayout extends BoxPanel(Orientation.Vertical) {
  val welcomePanel: FlowPanel = new FlowPanel() {
    contents += new Label("Welcome to your HOME") {
      font = new Font("Arial", 0, 36)
    }
  }
  val temperaturePanel: FlowPanel = new FlowPanel() {
    hGap = 70
    contents ++= Seq(new Label("Date: " + DateTime.getDate), new Label("Internal temperature: "))
    //contents += new Label("External temperature: ")
  }
  val humidityPanel: FlowPanel = new FlowPanel() {
    hGap = 70
    contents ++= Seq(new Label("Time: " + DateTime.getCurrentTime), new Label("Internal humidity: "))
    //contents += new Label("External humidity: ")
  }
  val alarmPanel: FlowPanel = new FlowPanel() {
    hGap = 70
    contents ++= Seq(new Label("Alarm status"), new ToggleButton())
  }
  val currentProfile = new Label("Current active profile: " + Coordinator.getActiveProfile.name)
  val profilePanel: FlowPanel = new FlowPanel() {
    hGap = 70
    contents ++= Seq(currentProfile,
      new Button("Change profile") {
        reactions += {
          case ButtonClicked(_) => ChangeOrDeleteProfile(this.text, currentProfile)
        }
      },
      new Button("Delete profile") {
        reactions += {
          case ButtonClicked(_) => ChangeOrDeleteProfile(this.text, currentProfile)
        }
      },
      new Button("Create profile") {
        reactions += {
          case ButtonClicked(_) => CreateProfile()
        }
      }
    )
  }
  contents ++= Seq(welcomePanel, temperaturePanel, humidityPanel, alarmPanel, profilePanel)
}
object HomePage {
  def apply(): HomePageLayout = {
    new HomePageLayout()
  }
}

/**
 * Graphical representation of a device and features, every device has its own implementation.
 *  @param d the device represented by this panel.
 */
abstract class GUIDevice(val d : Device) extends FlowPanel{
  override val name: String = d.name
  val ON = "ON"
  val OFF = "OFF"

  private val devType = new Label("DeviceType: "+d.deviceType)
  private val status: BinaryFeature = BinaryFeature(d.name,"ON",Msg.on,"OFF",Msg.off)
  lazy val close: Unit = this.visible = false

  border = new LineBorder(Color.BLACK, 2)
  contents += new DeviceIcon(d.deviceType.toString)
  private val deviceInfo = new GridPanel(2, 2)
  deviceInfo.contents ++= Seq(
    devType,
    new Label("Consumption: " + d.consumption),
    status,
    new Button("Delete") {
      reactions +={
        case ButtonClicked(_) => Dialog.showConfirmation(message="Are you sure you want to delete this device? There is no coming back",title ="Delete device") match{
          case Result.Ok =>  Coordinator.sendUpdate(d.name,Msg.disconnect); GUI.removeDevice(d); close
          case _ => //Do nothing
        }
      }
    }
  )

  if (!Device.isSensor(d)) contents += deviceInfo else contents += new Label("Value:")

  /**
   * Updates this device and its graphical representation.
   *
   * @param cmdString the command this device has to execute
   * @param newVal to set the property to.
   *
   * Provides the two basic commands every device needs to respond to;
   * called whenever a profile needs to update a device.
   */
  def updateDevice(cmdString: String,newVal:String): Unit = cmdString match {
    case Msg.on => d.turnOn(); status.setVal("ON")
    case Msg.off => d.turnOff(); status.setVal("OFF")
    case _ =>
  }

  /** An icon inside a label.
   *
   * images are stored inside a resource folder and accessed by deviceType.
   * @param iconName type of icon to load
   *
   * Icons are stored under deviceType name.
   */
  private class DeviceIcon(iconName :String) extends Label{
    text=iconName
    border = new LineBorder(Color.black,1)
    icon = new ImageIcon(getClass.getClassLoader.getResource(iconName + Constants.IconExt) getPath)

    horizontalTextPosition = Alignment.Center
    verticalTextPosition = Alignment.Bottom
  }
  def updateDevice(dev: Device, cmdString: String,newVal:String): Unit = cmdString match {
    case Msg.on => d.turnOn(); status.setVal("ON")
    case Msg.off => d.turnOff(); status.setVal("OFF")
    case _ =>
  }
}
/** A device feature that can be changed either by user or profile.
 *
 * @param deviceName name of the device this feature belongs to.
 * @param featureTitle name of such feature
 * @param initialValue current value to display
 * @param setterComponent component through which users can change feature val
 *                        @tparam A Component with the ability to send the update to coordinator whenever needed.
 * @param updateType type of update([[Msg]]) this feature sends to coordinator
 *
 *  once a click is performed on this label, [[setterComponent]] is opened and user can change feature value; if so,
 *  such change is sent to coordinator via [[Promise]]. When the promise completes, this feature value is updated.
 */
class DeviceFeature[A <: Component with EditableFeature](deviceName :String,featureTitle : String, initialValue: String, setterComponent: A ,updateType:String) extends Label {
  text = initialValue
  border = new LineBorder(Color.black,1)
  reactions+={
    case MouseClicked(_,_,_,_,_) => new Dialog(){
      title = featureTitle
      private val value : Label = new Label(setterComponent.getVal)

      contents = new BoxPanel(Orientation.Vertical) {
        contents ++= Seq(
          new FlowPanel() {
            contents ++= Seq(
              new Label("Set "+featureTitle+": "),
              setterComponent,
              value
            )
          },
          new FlowPanel() {
            contents ++= Seq(
              new Button("Confirm") {
                reactions += {
                  case ButtonClicked(_) => setterComponent.update(deviceName,updateType,setterComponent.getVal).onComplete{
                    case Success(_) => setFeatureValue(setterComponent.getVal); close()
                    case _ => Dialog.showMessage(title = "Update error",message = "Something went wrong while updating a device",messageType= Message.Error)
                  }
                }
              },
              new Button("Cancel") {
                reactions += {
                  case ButtonClicked(_) => close()
                }
              })
          }
        )
      }
      reactions+={
        case ValueChanged(_) => value.text = setterComponent.getVal;
      }
      listenTo(setterComponent)
      open()
    }
  }
  listenTo(mouse.clicks)
  this.visible = true
  def setFeatureValue(c :String): Unit = text = c
}

/** Factory for [[DeviceFeature]] istances.
 *
 */
object Feature{
  /**
   *
   * @param devName see [[DeviceFeature]]
   * @param title see [[DeviceFeature]]
   * @param text see [[DeviceFeature]]
   * @param setterComponent see [[DeviceFeature]]
   * @param updateType see [[DeviceFeature]]
   * @tparam A see [[DeviceFeature]]
   * @return new DeviceFeature instance
   */
  def apply[A<: Component with EditableFeature](devName:String,title:String,text:String,setterComponent:A,updateType:String): DeviceFeature[A] = new DeviceFeature(devName,title,text,setterComponent,updateType)

  /** This overload is used by sensor which cannot be updated so they don't need an [[UpdateTypes]]
   *
   */
  def apply[A<: Component with EditableFeature](devName:String,title:String,text:String,setterComponent:A): DeviceFeature[A] = new DeviceFeature(devName,title,text,setterComponent,null)
}

/**
 * Factory for [[GUIDevice]] instancies.
 *
 * Given a device, returns its graphical representation
 */
object PrintDevicePane {
  /**
   *
   * @param device device to represent
   * @return GUIDevice representing device param
   *
   */
  def apply(device: Device) : GUIDevice = device.deviceType  match{
    case AirConditionerType => AirConditionerPane(AirConditioner(device.name,device.room))
    case DehumidifierType => DehumidifierPane(Dehumidifier(device.name,device.room))
    case DishWasherType => DishWasherPane(DishWasher(device.name,device.room))
    case LightType => LightPane(Light(device.name,device.room))
    case OvenType => OvenPane(Oven(device.name,device.room))
    case ShutterType => ShutterPane(Shutter(device.name,device.room))
    case StereoSystemType => StereoPane(StereoSystem(device.name,device.room))
    case TvType => TVPane(TV(device.name,device.room))
    case WashingMachineType => WashingMachinePane(WashingMachine(device.name,device.room))
    case BoilerType => BoilerPane(Boiler(device.name,device.room))

    //Sensors
    case ThermometerType => ThermometerPane(device.asInstanceOf[SimulatedThermometer])
    case HygrometerType => HygrometerPane(device.asInstanceOf[SimulatedHygrometer])
    case MotionSensorType => MotionSensorPane(device.asInstanceOf[SimulatedMotionSensor])
    case PhotometerType => PhotometerPane(device.asInstanceOf[SimulatedPhotometer])
    case _ => this.errUnexpected(UnexpectedDeviceType, device.deviceType.toString)
  }
}

/* SENSORS' PANES*/
private case class HygrometerPane(override val d: SimulatedHygrometer)extends GUIDevice(d){
  require (d.deviceType == HygrometerType)
  private val MAX = 100
  private val MIN = 0
  contents += Feature(d.name,"Humidity",30 toString,new SliderFeature(MIN,MAX){
    override def update(devName : String, cmdMsg :String, newValue:String): Future[Unit] ={
      d.valueChanged(newValue toDouble)
      Promise[Unit].success(() => Unit).future
    }
  })

  override def updateDevice( cmdString: String, newVal: String): Unit = {}
}
private case class MotionSensorPane(override val d: SimulatedMotionSensor)extends GUIDevice(d){
  require (d.deviceType == MotionSensorType)
  private val EMPTY = "EMPTY"
  private val NOT_EMPTY = "NOT EMPTY"
  private var status = EMPTY
  contents += new BinaryFeature(d.name,"Empty",Msg.motionDetected,"NOT EMPTY",Msg.motionDetected){
    override def update(devName : String, cmdMsg :String, newValue:String): Future[Unit] ={
      status match {
        case EMPTY =>
          d.valueChanged(currentVal = true)
          Promise[Unit].success(() => Unit).future
          status = NOT_EMPTY;
        case _ =>
          d.valueChanged(currentVal = false)
          Promise[Unit].success(() => Unit).future
          status = EMPTY}
      text = status
      Promise[Unit].success(() => Unit).future
    }
  }
  override def updateDevice(cmdString: String, newVal: String): Unit = {}
}
private case class PhotometerPane(override val d: SimulatedPhotometer)extends GUIDevice(d){
  require (d.deviceType == PhotometerType)
  private val MAX = 100
  private val MIN = 0
  contents += Feature(d.name,"Temperature",22 toString,new SliderFeature(MIN,MAX){
    override def update(devName : String, cmdMsg :String, newValue:String): Future[Unit] ={
      d.valueChanged(newValue toDouble)
      Promise[Unit].success(() => Unit).future
    }
  })
  override def updateDevice(cmdString: String, newVal: String): Unit = {}
}
private case class ThermometerPane(override val d: SimulatedThermometer) extends GUIDevice(d){
  require (d.deviceType == ThermometerType)
  private val MAX = 50
  private val MIN = -20
  contents += Feature(d.name,"Temperature",22 toString,new SliderFeature(MIN,MAX){
     override def update(devName : String, cmdMsg :String, newValue:String): Future[Unit] ={
       d.valueChanged(newValue toDouble)
       Promise[Unit].success(() => Unit).future
    }
  }) //TODO: MAGIC NUMBERS
  override def updateDevice(cmdString: String, newVal: String): Unit = {}
}

/* DEVICES' PANE*/
private case class AirConditionerPane(override val d: SimulatedAirConditioner) extends GUIDevice(d){
  require (d.deviceType == AirConditionerType)
  private val temp = Feature(d.name,"Temperature",d.value toString,SliderFeature(d.minValue,d.maxValue),Msg.setTemperature)
  contents++=Seq(new Label("Temperature: "),
    temp)
  override def updateDevice(cmdString: String,newVal:String): Unit = {
    super.updateDevice(cmdString,newVal)
    cmdString match {
      case Msg.setTemperature => d.setValue(newVal toInt); temp.setFeatureValue(newVal);
      case _ => this.errUnexpected(UnexpectedMessage, "This device can only receive temperature updates")
    }
  }
}
private case class BoilerPane(override val d: SimulatedBoiler) extends GUIDevice(d){
  require (d.deviceType == BoilerType)
  private val waterTemp = Feature(d.name,"Water temperature",d.value toString,SliderFeature(d.minValue,d.maxValue),Msg.setHumidity)
  contents++=Seq(new Label("Water temperature: "),
    waterTemp)
  override def updateDevice(cmdString: String,newVal:String): Unit = {
    super.updateDevice(cmdString, newVal)
    cmdString match {
      case Msg.setTemperature => d.setValue(newVal toInt); waterTemp.setFeatureValue(newVal)
      case _ => this.errUnexpected(UnexpectedMessage, "This device can only receive temperature updates")
    }
  }
}
private case class DehumidifierPane(override val d: SimulatedDehumidifier) extends GUIDevice(d){
  require (d.deviceType == DehumidifierType)
  private val humidity = Feature(d.name,"Humidity",d.value toString,SliderFeature(d.minValue,d.maxValue),Msg.setHumidity)
  contents++=Seq(new Label("Humidity %: "),
    humidity)
  override def updateDevice(cmdString: String,newVal:String): Unit = {
    super.updateDevice(cmdString, newVal)
    cmdString match {
      case Msg.setHumidity => d.setValue(newVal toInt); humidity.setFeatureValue(newVal)
      case _ => this.errUnexpected(UnexpectedMessage, "This device can only receive humidity updates")
    }
  }
}
private case class DishWasherPane(override val d: SimulatedDishWasher) extends GUIDevice(d){
  require (d.deviceType == DishWasherType)
  private val washProgram = Feature(d.name,"Washing program",d.getWashingProgram toString,ListFeature(Seq(DishWasherProgram.DIRTY,DishWasherProgram.FAST,DishWasherProgram.FRAGILE)map(_ toString)),Msg.setProgram)
  private val extras = Feature(d.name,"Extra","Extra",ListFeature(Seq(DishWasherExtra.SuperDirty,DishWasherExtra.SuperHygiene,DishWasherExtra.SuperSteam)map(_ toString)),Msg.addExtra)
  contents++= Seq(
    new Label("Washing program: "),
    washProgram,
    new Label("Extras: "),
    extras
  )
  override def updateDevice(cmdString: String,newVal:String): Unit = {
      super.updateDevice(cmdString, newVal)
      cmdString match {
        case Msg.washingType => d.setWashingProgram(DishWasherProgram(newVal)); washProgram.setFeatureValue(newVal)
        case Msg.addExtra => d.addExtra(DishWasherExtra(newVal)); extras.setFeatureValue(newVal)
        case _ => this.errUnexpected(UnexpectedMessage, "This device can only receive washing type or extra updates")
      }
  }
}
private case class LightPane(override val d: SimulatedLight) extends GUIDevice(d) {
  require(d.deviceType == LightType)
  private val intensity = Feature(d.name,"Intensity",d.value toString,SliderFeature(d.minValue,d.maxValue),Msg.setIntensity)
  contents++=Seq(new Label("Intensity: "),
    intensity)
  override def updateDevice(cmdString: String,newVal:String): Unit = {
    super.updateDevice(cmdString, newVal)
    cmdString match {
      case Msg.setIntensity => d.setValue(newVal toInt); intensity.setFeatureValue(newVal)
      case _ => this.errUnexpected(UnexpectedMessage, "This device can only receive intensity updates")
    }
  }
}
private case class OvenPane(override val d: SimulatedOven) extends GUIDevice(d){
  require (d.deviceType == OvenType)
  private val ovenTemp = Feature(d.name,"Oven temperature",d.value toString, SliderFeature(d.minValue,d.maxValue),Msg.setTemperature)
  private val ovenMode =Feature(d.name,"Oven mode",d.getOvenMode toString, ListFeature(Seq(OvenMode.CONVENTIONAL,OvenMode.DEFROSTING,OvenMode.GRILL,OvenMode.LOWER,
    OvenMode.UPPER,OvenMode.VENTILATED)map(_ toString)),Msg.setMode)
  contents++=Seq(
    new Label("Oven temperature: "),
    ovenTemp,
    new Label("Oven Mode: "),
    ovenMode
  )
  override def updateDevice(cmdString: String,newVal:String): Unit = {
    super.updateDevice(cmdString, newVal)
    cmdString match {
      case Msg.setTemperature => d.setValue(newVal toInt); ovenTemp.setFeatureValue(newVal)
      case Msg.setMode => d.setOvenMode(OvenMode(newVal)); ovenMode.setFeatureValue(newVal)
      case _ => this.errUnexpected(UnexpectedMessage, "This device can only receive temperature or mode updates")
    }
  }
}
private case class ShutterPane(override val d: SimulatedShutter) extends GUIDevice(d){
  private val mode = BinaryFeature(d.name,"OPEN",Msg.close,"CLOSED",Msg.open)
  require (d.deviceType == ShutterType)
  contents+= mode
  override def updateDevice(cmdString: String,newVal:String): Unit = {
    super.updateDevice(cmdString, newVal)
    cmdString match {
      case Msg.open => d.open(); mode.setVal("OPEN")
      case Msg.close => d.close(); mode.setVal("CLOSED")
      case _ => this.errUnexpected(UnexpectedMessage, "This device can only receive close or open updates")
    }
  }
}
private case class StereoPane(override val d: SimulatedStereoSystem) extends GUIDevice(d){
  private val volume = Feature(d.name,"Volume",d.value toString,SliderFeature(d.minValue,d.maxValue),Msg.setVolume)
  private val muted = BinaryFeature(d.name,"NOT MUTED",Msg.mute,"MUTED",Msg.mute)
  contents++=Seq(
    new Label("Volume: "),
    volume,
    muted
  )
  override def updateDevice(cmdString: String,newVal:String): Unit = {
    super.updateDevice(cmdString, newVal)
    cmdString match {
      case Msg.setVolume => d.setValue(newVal toInt); volume.setFeatureValue(newVal)
      case Msg.mute => d.setValue(d.minValue)
      case _ => this.errUnexpected(UnexpectedMessage, "This device can only receive volume updates")
    }
  }
}
private case class TVPane(override val d: SimulatedTV) extends GUIDevice(d){
  require (d.deviceType == TvType)
  private val volume =Feature(d.name,"Volume",d.value toString,SliderFeature(d.minValue,d.maxValue),Msg.setVolume)
    private val muted = BinaryFeature(d.name,"NOT MUTED",Msg.mute,"MUTED",Msg.mute)
  contents++=Seq(
    new Label("Volume: "),
    volume,
    muted
  )
  override def updateDevice(cmdString: String,newVal:String): Unit = {
    super.updateDevice(cmdString,newVal)
    cmdString match{
    case Msg.setVolume => d.setValue(newVal toInt); volume.setFeatureValue(newVal)
    case Msg.mute => d.setValue(d.minValue); muted.setVal("MUTED")
    case _ =>this.errUnexpected(UnexpectedMessage,"This device can only receive volume updates")
    }
  }
}
private case class WashingMachinePane(override val d: SimulatedWashingMachine) extends GUIDevice(d){
  private val workMode =Feature(d.name,"Working mode",d.getWashingType toString,ListFeature(Seq(WashingType.RAPID,WashingType.MIX,WashingType.WOOL)map(_ toString)),Msg.washingType)
  private val extras =Feature(d.name,"Extras","Extra",ListFeature(Seq(WashingMachineExtra.SpecialColors,WashingMachineExtra.SuperDirty,WashingMachineExtra.SuperDry)map(_ toString)),Msg.addExtra)
  private val rpm = Feature(d.name,"RMP",d.getRPM toString,ListFeature(Seq(RPM.FAST,RPM.MEDIUM,RPM.SLOW)map(_ toString)),Msg.RPM)

  require (d.deviceType == WashingMachineType)
  contents++= Seq(
    new Label("Working mode: "),
    workMode,
    new Label("Extras: "),
    extras,
    new Label("RPM: "),
    rpm
  )
  override def updateDevice(cmdString: String,newVal:String): Unit = {
    super.updateDevice(cmdString,newVal)
    cmdString match {
      case Msg.setMode => d.setWashingType(WashingType(newVal)); workMode.setFeatureValue(newVal)
      case Msg.addExtra => d.addExtra(WashingMachineExtra(newVal)); extras.setFeatureValue(newVal)
      case Msg.RPM => d.setRPM(RPM(newVal)); rpm.setFeatureValue(newVal)
      case _ => this.errUnexpected(UnexpectedMessage, "This device can only receive close or open updates")
    }
  }
}

/** Slider able to communicate with coordinator.
 *
 * @param mini min slider value
 * @param maxi max slider value
 */
case class SliderFeature(mini : Int, maxi: Int) extends Slider with EditableFeature {
  min = mini
  max = maxi
  override def getVal :String = value toString
  override def setVal(v:String): Unit = value = v toInt
}

/** ComboBox able to communicate with coordinator.
 *
 * @param items to be displayed in [[ComboBox]]
 */
case class ListFeature(items: Seq[String]) extends ComboBox(items) with EditableFeature {
  override def getVal : String = selection.item
  override def setVal(v: String): Unit = selection.item = v
}

/** ToggleButton able to comunicate with coordinator.
 *
 * @param devName device name this feature belongs to
 * @param toDisplay first possible value
 * @param displayCmd [[Msg]] to send when switching to [[toDisplay]]
 * @param other second possible value
 * @param otherCmd [[Msg]] to send when switching to [[other]]
 *
 * Represents a binary feature of a device (es. Yes/No), see [[ShutterPane]]
 */
case class BinaryFeature(devName:String,toDisplay:String,displayCmd:String,other : String,otherCmd:String) extends ToggleButton with EditableFeature {
  override def getVal: String = status
  override def setVal(v:String): Unit = {if(status!=v)this.doClick()}

  text = toDisplay
  private var status = toDisplay
  reactions += {
    case ButtonClicked(_) =>
      status match { case `toDisplay` => update(cmdMsg = otherCmd);status=other case _ => update(cmdMsg = displayCmd);status=toDisplay}
  }
  /** takes a function, applies it to status and returns new status value
   */
  val switchStatus: (String => Unit) => String = (c: String => Unit) => {
    c(status)
    status
  }

  /** sends update requested by user via GUI to [[Coordinator]]
   * @param devName device requiring update
   * @param cmdMsg [[Msg]] update type
   * @param newValue new feature value.
   * @return [[Future]] representing when the connected device confirms the update
   *
   * Overriding [[EditableFeature]] update since being a two value feature, no setter component is needed
   * and updates are generated whenever a click is performed on this button.
   */
  override def update(devName : String = devName,cmdMsg :String, newValue : String = switchStatus{case `toDisplay` => status = other case _ => status = toDisplay}): Future[Unit] ={
    val p = Promise[Unit]
    Coordinator.sendUpdate(devName,cmdMsg).onComplete {
      case Success(_) => text = status; p.success(()=>Unit)
      case _ => Dialog.showMessage(title = "Update error",message = "Something went wrong while updating a device",messageType= Message.Error)
    }
    p.future
  }
}

/** Login page
 *
 */
object LoginPage{
  val id : TextField = new TextField(Constants.LoginTextSize)
  val psw : PasswordField = new PasswordField(Constants.LoginTextSize)

  new Frame(){
    title = "Login to HOME!"
    contents = new BoxPanel(Orientation.Vertical) {
      contents ++= Seq(
        new FlowPanel() {
          contents ++= Seq(
            new Label("Username:"),
            id,
          )
        },
        new FlowPanel() {
          contents ++= Seq(
            new Label("Password:"),
            psw,
          )},
        new FlowPanel() {
          contents ++= Seq(
            new Button("Confirm"),
            new Button("Cancel") {
              reactions += {
                case ButtonClicked(_) => close()
              }
            })
        }
      )
    }
    this.open()
  }
}