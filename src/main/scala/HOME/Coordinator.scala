package HOME

import HOME.CommandMsg.CommandMsgImpl
import HOME.MyClass._

import scala.collection.mutable.ListBuffer
import scala.concurrent.{Future, Promise}

object Coordinator extends JSONSender with MQTTUtils {
  override def senderType: SenderType = SenderTypeCoordinator
  override def name: String = "Coordinator"
  override def lastWillTopic: String = broadcastTopic
  override def lastWillMessage: String = Msg.disconnected

  var devices: Set[Device] = Set.empty
  var activeProfile: Profile = Profile(Constants.default_profile_name)
  var subTopics: ListBuffer[String] = new ListBuffer[String]()

  def getActiveConsumption: Int = getConsumption(getDevices.filter(_.isOn).toList)
  private def getConsumption(seq: Seq[Device]): Int = seq.map(_.consumption).sum

  //DEVICES
  def addDevice(devType: String,name:String,room : String): Option[Device] = {
    val dev: Option[Device] = Device(devType, name, room)
    dev match {
      case Some(device) => devices += device;
      case _ => None
    }
    dev
  }
  def addDevice(device: Device): Unit = devices += device

  def removeDevice(device: String): Unit = devices --= devices.filter(_.name == device)
  def removeAllDevices(): Unit = devices = Set.empty

  def getDevices: Set[Device] = devices

  //PROFILES

  def getActiveProfile: Profile = activeProfile
  def setProfile(newProfile: Profile): Unit = newProfile match {
    case profile: Profile if profile != activeProfile =>
      activeProfile = newProfile
      activeProfile.onActivation ()
    case _ =>
  }

  //MQTT

  def connect: Boolean = connect(this, onMessageReceived)

  def subscribe: Boolean = subscribe(regTopic) && subscribe(updateTopic)

  def publish(device: AssociableDevice, message: CommandMsg): Boolean = publish(device.getSubTopic, message, this, !retained)
  def publish(topic: String, message: String): Boolean = publish(topic, message, this)

  def onMessageReceived(topic: String, message: String): Unit = topic match {
    case t if t == regTopic => handleRegMsg(message)
    case t if t == updateTopic => RequestHandler.handleRequest(CommandMsg.fromString(getMessageFromMsg(message)).id)
    case _ if isSensorUpdate(topic, message) =>
      val msg = CommandMsg.fromString(getMessageFromMsg(message))
      val device = getSenderFromMsg[AssociableDevice](message)
      device.deviceType match {
        case ThermometerType => Coordinator.activeProfile.onThermometerNotification(device.room, msg.value.toInt)
        case HygrometerType => Coordinator.activeProfile.onHygrometerNotification(device.room, msg.value.toInt)
        case PhotometerType => Coordinator.activeProfile.onPhotometerNotification(device.room, msg.value.toInt)
        case MotionSensorType => Coordinator.activeProfile.onMotionSensorNotification(device.room, msg.value.toBoolean)
        case _ => this.errUnexpected(UnexpectedDeviceType, device.deviceType.getSimpleClassName)
      }
    case _ => this.errUnexpected(UnexpectedTopic, topic)
  }

  private def isSensorUpdate(topic: String, message: String): Boolean = {
    val split = topic.split(topicSeparator)
    split.length > 1 && DeviceType.isSensor(split(1)) && message.contains(Msg.updateBaseString)
  }

  def sendUpdate(devName : String,cmdMsg : String,newValue:String = null) : Future[Unit] = {
    val p = Promise[Unit]
    val requestNumber = RequestHandler.addRequest(p)
    publish(devices.find(_.name equals devName).get.asInstanceOf[AssociableDevice],CommandMsgImpl(requestNumber, cmdMsg, newValue))
    RequestHandler.addRequest(p)
    p.future
  }

  def handleRegMsg(msg: String): Unit = {
    val device: AssociableDevice = getSenderFromMsg[AssociableDevice](msg)
    if (device == null) this.errUnexpected(UnexpectedDevice, null)

    getMessageFromMsg(msg) match {
      case m if m == Msg.register =>
        addDevice(device)
        subscribe(device.getPubTopic)
        publish(device.getSubTopic, Msg.regSuccess)
      case m if m == Msg.disconnected =>
        removeDevice(device.name)
        unsubscribe(device.getPubTopic)
      case m => this.errUnexpected(UnexpectedMessage, m)
    }
  }
}

object Rooms {
  private var _allRooms: Set[String] = Set.empty

  def addRoom(room: String): Unit = _allRooms += room
  def removeRoom(room: String): Unit = _allRooms -= room  //TODO remove all devices in the room
  def allRooms: Set[String] = _allRooms
}
////////////////
/// PROFILES ///
////////////////

sealed trait Profile {
  val name: String
  val description: String

  def onActivation(): Unit

  def onThermometerNotification(room: String, value: Double): Unit
  def onHygrometerNotification(room: String, value: Double): Unit
  def onPhotometerNotification(room: String, value: Double): Unit
  def onMotionSensorNotification(room: String, value: Boolean): Unit

  def doProgrammedRoutine(): Unit

  override def equals(o: Any): Boolean = o match {
    case profile: Profile if this.name == profile.name => true
    case _ => false
  }
}

//////////////////////
/// BASIC PROFILES ///
//////////////////////

trait BasicProfile extends Profile {
  val initialRoutine: Device => Unit
  def thermometerNotificationCommands(room: String,value: Double): Device => Unit
  def hygrometerNotificationCommands(room: String,value: Double): Device => Unit
  def photometerNotificationCommands(room: String,value: Double): Device => Unit
  def motionSensorNotificationCommands(room: String, value: Boolean): Device => Unit

  val programmedRoutineCommands: Device => Unit

  def applyCommand(command: Device => Unit): Unit = {
      for (device <- Coordinator.getDevices) {
        command(device)
      }
  }

  override def onActivation(): Unit = applyCommand(initialRoutine)

  override def onThermometerNotification(room: String, value: Double): Unit = applyCommand(thermometerNotificationCommands(room,value))
  override def onHygrometerNotification(room: String, value: Double): Unit = applyCommand(hygrometerNotificationCommands(room,value))
  override def onPhotometerNotification(room: String, value: Double): Unit = applyCommand(photometerNotificationCommands(room,value))
  override def onMotionSensorNotification(room: String, value: Boolean): Unit = applyCommand(motionSensorNotificationCommands(room, value))
}

object Profile {
  var savedProfiles: Set[Profile] = Set(DEFAULT_PROFILE, NIGHT)

  def getProfiles: Set[Profile] = savedProfiles
  def getProfile(name: String): Option[Profile] = savedProfiles.find(_.name == name)
  def addProfile(profile: Profile): Unit = savedProfiles += profile
  def removeProfile(name: String): Unit = savedProfiles -= { getProfile(name) match {
    case Some(value) => value
    case _ => null
    }
  }

  private case object DEFAULT_PROFILE extends BasicProfile  {

    override val name: String = Constants.default_profile_name
    override val description: String = "Default Profile"

    override val initialRoutine: Device => Unit = _.id
    override val programmedRoutineCommands: Device => Unit = _.id

    override def thermometerNotificationCommands(room: String, value: Double): Device => Unit = _.id
    override def hygrometerNotificationCommands(room: String, value: Double): Device => Unit = _.id
    override def photometerNotificationCommands(room: String, value: Double): Device => Unit = _.id
    override def motionSensorNotificationCommands(room: String, value: Boolean): Device => Unit = _.id

    override def doProgrammedRoutine(): Unit = {}
  }

  private case object NIGHT extends BasicProfile  {

    override val name: String = "NIGHT"
    override val description: String = "Night Profile"

    override val initialRoutine: Device => Unit = {
      case device: AssociableDevice if device.deviceType == ShutterType => Coordinator.publish(device, CommandMsg(cmd = Msg.close)); Coordinator.publish(device, CommandMsg(cmd = Msg.off))
      case device: AssociableDevice if device.deviceType == AirConditionerType => Coordinator.publish(device, CommandMsg(cmd = Msg.on)); Coordinator.publish(device, CommandMsg(Msg.nullCommandId, Msg.setTemperature, 21))
      case device: AssociableDevice if device.deviceType == DehumidifierType => Coordinator.publish(device, CommandMsg(cmd = Msg.on)); Coordinator.publish(device, CommandMsg(Msg.nullCommandId, Msg.setHumidity, 40))
      case device: AssociableDevice => Coordinator.publish(device, CommandMsg(cmd = Msg.off))
    }

    //TODO REPLACE _.id , SHOULD BE NULL OR SOMETHING
    override def thermometerNotificationCommands(room: String, value: Double): Device => Unit = _.id
    override def hygrometerNotificationCommands(room: String, value: Double): Device => Unit = _.id
    override def photometerNotificationCommands(room: String, value: Double): Device => Unit = {
      case device: AssociableDevice if device.room == room && device.deviceType == ShutterType && value > Constants.dayLightValue =>
        Coordinator.setProfile(Profile(Constants.default_profile_name))
      case _ =>
    }

    override def motionSensorNotificationCommands(room: String, value: Boolean): Device => Unit = {
      case device: AssociableDevice if value && device.room == room && device.deviceType == LightType =>
        Coordinator.publish(device, CommandMsg(cmd = Msg.on))
        Coordinator.publish(device, CommandMsg(Msg.nullCommandId, Msg.setIntensity, 30))
      case _ =>
    }

    override val programmedRoutineCommands: Device => Unit = null

    override def doProgrammedRoutine(): Unit = {}
  }

  def apply(name: String): Profile = getProfiles.find(_.name == name) match {
    case Some(t) => t
    case _ => this.errUnexpected(UnexpectedProfile, name)
  }
}

///////////////////////
/// CUSTOM PROFILES ///
///////////////////////

case class CustomProfile(override val name: String, override val description: String,
                         initialRoutineSet: Set[Device => Unit],
                         thermometerNotificationCheckAndCommandsSet: Map[Double => Boolean, Set[Device => Unit]],
                         hygrometerNotificationCheckAndCommandsSet: Map[Double => Boolean, Set[Device => Unit]],
                         photometerNotificationCheckAndCommandsSet: Map[Double => Boolean, Set[Device => Unit]],
                         motionSensorNotificationCommandsSet:Set[Device => Unit],
                         programmedRoutineCommandsSet: Set[Device => Unit],
                         override val doProgrammedRoutine: Unit) extends Profile {

  val initialRoutine: Set[Device => Unit] = initialRoutineSet
  def thermometerNotificationCommands: Map[Double => Boolean, Set[Device => Unit]] = thermometerNotificationCheckAndCommandsSet
  def hygrometerNotificationCommands: Map[Double => Boolean, Set[Device => Unit]] = hygrometerNotificationCheckAndCommandsSet
  def photometerNotificationCommands: Map[Double => Boolean, Set[Device => Unit]] = photometerNotificationCheckAndCommandsSet
  def motionSensorNotificationCommands: Set[Device => Unit] = motionSensorNotificationCommandsSet

  val programmedRoutineCommands: Set[Device => Unit] = programmedRoutineCommandsSet

  override def onActivation(): Unit = applyCommand(initialRoutine)

  //if required condition for value is fulfilled apply the commands in given room
  override def onThermometerNotification(room: String, value: Double): Unit = checkAndApplySensorCommand(value, thermometerNotificationCommands, room)
  override def onHygrometerNotification(room: String, value: Double): Unit = checkAndApplySensorCommand(value, hygrometerNotificationCommands, room)
  override def onPhotometerNotification(room: String, value: Double): Unit = checkAndApplySensorCommand(value, photometerNotificationCommands, room)
  override def onMotionSensorNotification(room: String, value: Boolean): Unit = if(value) applySensorCommand(motionSensorNotificationCommands, room)

  private def applyCommand(commands: Set[Device => Unit], filter: Device => Boolean = _ => true ): Unit = {
    for(device <- Coordinator.getDevices.filter(filter)) {
      for(command <- commands) {
        command(device)
      }
    }
  }

  //only on devices in the sensor room
  private def applySensorCommand(commands: Set[Device => Unit], room: String): Unit = {
    applyCommand(commands, filter = _.room == room)
  }

  private def checkAndApplySensorCommand(value: Double, checkAndCommands: Map[Double => Boolean, Set[Device => Unit]], room: String): Unit = {
   for(checkAndCommand <- checkAndCommands) {
     if (checkAndCommand._1(value)) {
       applySensorCommand(checkAndCommand._2, room)
       return
     }
   }
  }

}

object CustomProfileBuilder {

  def generateCheckFunction(symbol: String, value: Double): Double => Boolean = symbol match {
    case "=" => {
      case double: Double if double == value => true
      case _ => false
    }
    case ">=" => {
      case double: Double if double >= value => true
      case _ => false
    }
    case "<=" => {
      case double: Double if double <= value => true
      case _ => false
    }
    case "<" => {
      case double: Double if double < value => true
      case _ => false
    }
    case ">" => {
      case double: Double if double > value => true
      case _ => false
    }
    case _ => this.errUnexpected(UnexpectedValue, symbol)
  }

  //Set of device and command
  def generateCommandSet(commands: Set[(Device,CommandMsg)]): Set[Device => Unit] = {
    var result: Set[Device => Unit] = Set.empty

    for(command <- commands) {
      val device: Device = command._1
      val message: CommandMsg = command._2

      result += {
        _.id match {
          case t if t == device.id => Coordinator.publish(device.asInstanceOf[AssociableDevice], message) //TODO asInstanceOf only cause simulated
          case _ =>
        }
      }
    }
    result
  }

  def generateSensorCommandsMap(checkAndCommands: (Double => Boolean, Set[Device => Unit])*): Map[Double => Boolean, Set[Device => Unit]] = {
    checkAndCommands.map(arg => arg._1 -> arg._2).toMap
  }

  def generateFromParams(name: String, description: String,
                         initialRoutine: Set[Device => Unit],
                         thermometerNotificationCommands: Map[Double => Boolean, Set[Device => Unit]],
                         hygrometerNotificationCommands: Map[Double => Boolean, Set[Device => Unit]],
                         photometerNotificationCommands: Map[Double => Boolean, Set[Device => Unit]],
                         motionSensorNotificationCommands: Set[Device => Unit], programmedRoutineCommands: Set[Device => Unit],
                         doProgrammedRoutine: Unit): Profile =  CustomProfile(name, description, initialRoutine,
                                                                              thermometerNotificationCommands,
                                                                              hygrometerNotificationCommands,
                                                                              photometerNotificationCommands,
                                                                              motionSensorNotificationCommands, programmedRoutineCommands, doProgrammedRoutine)
}
