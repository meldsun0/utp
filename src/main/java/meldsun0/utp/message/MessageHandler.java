package meldsun0.utp.message;

public interface MessageHandler<Message> {

  void handle(Message message);
}
