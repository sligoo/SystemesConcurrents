import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

import linda.*;
import linda.shm.MultiThreadLinda;

//---------------------------------------------------------------------------------------
public class AlignementSeq {
    static int NBPROC = 4;
    static int CHUNKSIZE = 100; // granularite de la bdd

    static int similitude(char [] s, char [] t) {
        int [][] tableSimilitude = new int [s.length][t.length];

        //System.out.println("s ("+s.length+") "+s);
        //System.out.println("t ("+t.length+") "+t);
        //initialisation
        tableSimilitude[0][0] = 0;
        //colonne 0
        for (int i=1 ; i<s.length ; i++) {
            tableSimilitude[i][0]=tableSimilitude[i-1][0]+ Sequence.suppression(s[i]);
        }
        //ligne 0
        for (int j=1 ; j<t.length ; j++) {
            tableSimilitude[0][j]=tableSimilitude[0][j-1]+Sequence.insertion(t[j]);
        }
        //remplissage ligne par ligne
        for (int i=1 ; i<s.length ; i++) {
            for (int j=1 ; j<t.length ; j++) {
                tableSimilitude[i][j]=Math.max(
                        tableSimilitude[i-1][j]+Sequence.suppression(s[i]),
                        Math.max(tableSimilitude[i][j-1]+Sequence.insertion(t[j]),
                            tableSimilitude[i-1][j-1]+Sequence.correspondance(s[i],t[j])));
            }
        }
        // résultat (minimal : on pourrait aussi donner le chemin et les transformations,
        // mais on se limite à l'essentiel)
        return tableSimilitude[s.length-1][t.length-1];
    }

    public static void main(String[] args) throws InterruptedException {
        long départ, fin;
        int résultat;

        final Linda linda = new linda.shm.CentralizedLinda();
        BDSequences BDS = new BDSequences();
        BDSequences cible = new BDSequences();

        if (args.length == 2) { //analyse des paramètres
            try {
                BDS.lier(args[0]);
                cible.lier(args[1]);
            }
            catch (IOException iox) {
                throw new IllegalArgumentException("Usage : AlignementSeq <chemin BD> <chemin cible>");
            }
        }
        if (BDS.estVide() || cible.estVide())
                throw new IllegalArgumentException("Usage : AlignementSeq <chemin BD> <chemin cible>");

        //appel correct
        départ = System.nanoTime();
        résultat = AlignementSeq.AMono(BDS,cible,0);
        fin = System.nanoTime();
        System.out.println("test mémoire monoactivité : durée = "+ (fin-départ) /1_000+
                                "µs -> résultat : " + résultat);
                           
        départ = System.nanoTime();
        résultat = AlignementSeq.AMonoLinda(BDS,cible,0,linda);
        fin = System.nanoTime();
        System.out.println("test linda monoactivité : durée = "+ (fin-départ) /1_000+
                                "µs -> résultat : " + résultat);

        départ = System.nanoTime();
        résultat = AlignementSeq.AMultiLinda(BDS,cible,0,new MultiThreadLinda(5));
        fin = System.nanoTime();
        System.out.println("test linda multiactivité : durée = "+ (fin-départ) /1_000+
                "µs -> résultat : " + résultat);
    }

    static int AMono(BDSequences BD,BDSequences BDcibles,int position) {
        // version "directe", sans Linda, donnée à titre de documentation/spécification
        int score=0;
        int résultat=0;
        Sequence res = null;
        Sequence courant = null;
        Sequence cible = BDcibles.lire(position);
        Iterator<Sequence> it = BD.itérateur();

        while (it.hasNext()) {
            courant = it.next();
            score = similitude(courant.lireSéquence().toCharArray(),
                                cible.lireSéquence().toCharArray());
            if (score > résultat) {
                res = courant;
                résultat = score ;
            }
        }
        System.out.println("cible : "+cible.afficher());
        System.out.println("résultat ("+résultat+"/ "+
                100*résultat/(cible.lireTailleSeq()*
                    Sequence.correspondance('A','A'))+
                "%): "+res.afficher());
        return résultat;
    }

    static int AMonoLinda(BDSequences BD,BDSequences BDcibles,int position,Linda l) {
        /* Cette version fait transiter les données par l'espace de tuples,
         * ce qui correspond effectivement au contexte d'exécution où l'on se place.
         * Elle est clairement moins efficace que la précédente
         * (passage par Linda, boucles distinctes pour la lecture et le traitement...),
         * mais représente une base pour une parallélisation.
         *
         * Noter que cette version peut/doit être adaptée (simplement), pour permettre de
         * traiter un volume de données supérieur à la mémoire disponible.
         */

        int score=0;
        int résultat=0;
        Sequence courant = null;
        Sequence cible = BDcibles.lire(position);
        Iterator<Sequence> it = BD.itérateur();
        Tuple tCourant = null;
        Tuple tCible = null;
        Tuple tRes = null;

        //déposer la cible dans l'espace de tuples
        l.write(new Tuple("cible",cible.lireSéquence(),
                            cible.afficher(),cible.lireTailleSeq()));

        //déposer les séquences dans l'espace de tuples
        while (it.hasNext()) {
            courant = it.next();
            l.write(new Tuple("BD",courant.lireSéquence(),courant.afficher()));
        }

        //chercher la meilleure similitude
        tCible = l.take(new Tuple("cible", String.class, String.class, Integer.class));
        tCourant = l.tryTake(new Tuple("BD",String.class,String.class));
        while (tCourant != null) {
            score = similitude(((String)tCourant.get(1)).toCharArray(),
                               ((String)tCible.get(1)).toCharArray());
            if (score > résultat) {
                tRes = tCourant;
                résultat = score ;
            }
            tCourant = l.tryTake(new Tuple("BD",String.class,String.class));
        }
        System.out.println("cible : "+tCible.get(2));
        System.out.println("résultat ("+résultat+"/ "+
                           100*résultat/(((Integer)tCible.get(3))*
                               Sequence.correspondance('A','A'))+
                           "%): "+tRes.get(2));
        return résultat;
    }

    static int AMultiLinda(BDSequences BD,BDSequences BDcibles,int position,Linda l) {
        int résultat=0;
        Sequence courant = null;
        Sequence cible = BDcibles.lire(position);
        Iterator<Sequence> it = BD.itérateur();
        Tuple tCible = null;
        Tuple tRes = null;
        int nbSeq = 0;
        List<Future<Tuple>> results = new ArrayList<>();

        //déposer la cible dans l'espace de tuples
        l.write(new Tuple("cible",cible.lireSéquence(),
                cible.afficher(),cible.lireTailleSeq()));

        //Création de la pool de theads
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(NBPROC);

        //chercher la meilleure similitude
        tCible = l.take(new Tuple("cible", String.class, String.class, Integer.class));

        while (it.hasNext()) {
            //remplir l'espace de tuples
            while (nbSeq < CHUNKSIZE && it.hasNext()) {
                courant = it.next();
                nbSeq++;
                l.write(new Tuple("BD",courant.lireSéquence(),courant.afficher()));
            }

            //lancer le calcul de similitudes
            for (int i = 0 ; i < nbSeq ; i++) {
                Task task = new Task(l, tCible);
                Future<Tuple> result = executor.submit(task);
                results.add(result);
                nbSeq--;
            }

            //traitement des resultats, on recupere le max "local" (de la chunk)
            for (Future<Tuple> r : results) {
                try {
                    int futureResult = (int) r.get().get(0);

                    if (futureResult > résultat) {
                        résultat = futureResult;
                        tRes = (Tuple) r.get().get(1);
                    }
                } catch(Exception e) {
                    e.printStackTrace();
                }
            }
            results.clear(); //pour eviter d'avoir toute la bd en memoire
        }

        executor.shutdown();

        System.out.println("cible : "+tCible.get(2));
        System.out.println("résultat ("+résultat+"/ "+
                100*résultat/(((Integer)tCible.get(3))*
                        Sequence.correspondance('A','A'))+
                "%): "+tRes.get(2));
        return résultat;
    }
}
