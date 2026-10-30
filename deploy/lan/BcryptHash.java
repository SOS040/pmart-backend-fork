// init-secrets.sh 가 쓰는 bcrypt 해시 생성기(LAN 첫 관리자 비밀번호 → {bcrypt}... 해시). 비밀번호는 표준 입력으로 받는다(인자·로그에 남기지 않음).
// 실행: java -cp <spring-security-crypto.jar> deploy/lan/BcryptHash.java < password-file
import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.springframework.security.crypto.bcrypt.BCrypt;

class BcryptHash {
  public static void main(String[] args) throws Exception {
    String password = new BufferedReader(new InputStreamReader(System.in)).readLine().strip();
    System.out.println("{bcrypt}" + BCrypt.hashpw(password, BCrypt.gensalt(10)));
  }
}
