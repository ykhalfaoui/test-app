Avec **la dernière version de Lombok (1.18.30+)**, voici la meilleure approche :

## **Solution pour Lombok 1.18.30+**

```java
@SuperBuilder
@Data
public class MaClass extends ClasseExterne {
    private String monChamp;
    
    // Lombok génère automatiquement un builder abstract static
    // Pas besoin de configuration supplémentaire
}
```

Depuis la dernière version, Lombok gère mieux `@SuperBuilder` et **génère automatiquement un builder `abstract static`**, ce qui satisfait Sonar.

## **Si tu as encore une alerte Sonar**

Ajoute cette annotation au niveau de la classe :

```java
@SuperBuilder
@Data
@SuppressWarnings("squid:S6206") // ou la règle exacte
public class MaClass extends ClasseExterne {
    private String monChamp;
}
```

## **Pour l'héritage d'une classe sans @SuperBuilder**

Assure-toi que la classe parente a au minimum un constructeur sans arguments :

```java
@SuperBuilder
@Data
public class MaClass extends ClasseExterne {
    private String monChamp;
}
```

**Quel est exactement le message d'erreur Sonar que tu reçois ?** (le code de la règle : `squid:S...` ?)

Et peux-tu vérifier ta version de Lombok avec :
```bash
mvn dependency:tree | grep lombok
```

Ça m'aidera à affiner la solution !
