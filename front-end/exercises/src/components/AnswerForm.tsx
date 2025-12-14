import React from 'react';
import { useState, useEffect } from 'react';
import { Question } from '../types';
import FillInBlank from './FillInBlank';
import Numeric from './Numeric';
import TrueFalse from './TrueFalse';
import MultipleChoice from './MultipleChoice';
import Checkbox from './CheckBox';

interface AnswerFormProps {
  tokenRef: React.MutableRefObject<string | null>;
  question: Question;
  page: number;
  setPage: (page: number) => void;
}

const AnswerForm: React.FC<AnswerFormProps> = ({ tokenRef, question, page, setPage }) => {
  const [studentAnswer, setStudentAnswer] = useState<string>('');
  const [responseHtml, setResponseHtml] = useState<string | null>(null);
  const [waiting, setWaiting] = useState<boolean>(false);

  // Define fetchExplanation globally so it can be called from HTML onclick handlers
  useEffect(() => {
    // Lazy-load MathJax only on first explanation request
    let mathJaxLoaded = false;
    const loadMathJax = () => {
      if (mathJaxLoaded || (window as any).MathJax) return;
      mathJaxLoaded = true;
      
      // Load polyfill for ES6 compatibility
      const polyfill = document.createElement('script');
      polyfill.src = 'https://polyfill.io/v3/polyfill.min.js?features=es6';
      polyfill.async = true;
      document.head.appendChild(polyfill);
      
      // Configure and load MathJax
      (window as any).MathJax = {
        tex: { inlineMath: [['$', '$'], ['\\(', '\\)']] },
        svg: { fontCache: 'global' }
      };
      
      const script = document.createElement('script');
      script.id = 'MathJax-script';
      script.async = true;
      script.src = 'https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js';
      document.head.appendChild(script);
    };
    
    (window as any).fetchExplanation = async (qid: number, param?: number) => {
      // Load MathJax on first call
      loadMathJax();
      
      const btn = document.getElementById('explainBtn') as HTMLButtonElement;
      if (btn) {
        btn.disabled = true;
        btn.textContent = 'Please wait a moment...';
      }
      const url = '/exercises?UserRequest=Explanation&qid=' + qid + (param == null ? '' : '&p=' + param);
      try {
        const response = await fetch(url);
        const data = await response.text();
        const explanation = document.getElementById('explanation');
        if (explanation) {
          explanation.innerHTML = data;
          // Reprocess math in newly loaded content
          if ((window as any).MathJax) {
            (window as any).MathJax.typesetPromise([explanation]).catch((err: any) => console.log(err));
          }
        }
        if (btn) {
          btn.style.display = 'none';
        }
      } catch (error) {
        console.error('Error fetching explanation:', error);
        if (btn) {
          btn.style.display = 'none';
        }
      }
    };
  }, []);

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    setWaiting(true);
    submitAnswer();
    setPage(2); // Show the response
  };

  const handleContinue = () => {
    question.prompt = '';
    question.type = '';
    setStudentAnswer(''); // Clear the answer
    setResponseHtml(''); // Clear the response
    setPage(1); // Go to the next question
  };

  const submitAnswer = async () => {
    try {
      console.log("Submitting: " + studentAnswer);
      const response = await fetch('/exercises', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': 'Bearer ' + tokenRef.current,
        },
        body: JSON.stringify({
          id: question.id,
          answer: studentAnswer,
          ...(question.parameter && { parameter: question.parameter })
        }),
      });
      const data = await response.json();
      tokenRef.current = data.token;
      setWaiting(false);
      if (!tokenRef.current) console.log(data.error,question);
      else setResponseHtml(data.html);
    } catch (error) {
      console.error('Error submitting answer:', error);
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', fontSize: '1em', fontWeight: 'bold' }}>
      {page === 1 && (
        (question.type === 'fill_in_blank' && <FillInBlank setStudentAnswer={setStudentAnswer} handleSubmit={handleSubmit} />) ||
        (question.type === 'numeric' && <Numeric units={question.units} setStudentAnswer={setStudentAnswer} handleSubmit={handleSubmit} />) ||
        (question.type === 'true_false' && <TrueFalse setStudentAnswer={setStudentAnswer} handleSubmit={handleSubmit} />) ||
        (question.type === 'multiple_choice' && <MultipleChoice choices={question.choices} scrambled={question.scrambled} setStudentAnswer={setStudentAnswer} handleSubmit={handleSubmit} />) ||
        (question.type === 'checkbox' && <Checkbox choices={question.choices} scrambled={question.scrambled} setStudentAnswer={setStudentAnswer} handleSubmit={handleSubmit} />)
      )}
      {page === 2 && (
        <div>
          <span>Your Answer:&nbsp;
            {
              question.type === 'multiple_choice' ? <span dangerouslySetInnerHTML={{ __html: question.choices[studentAnswer.charCodeAt(0) - 97] }} /> :
              question.type === 'checkbox' ? (
              <ul>
                {studentAnswer.split('').map((char: string, index: number) => (
                <li key={index} dangerouslySetInnerHTML={{ __html: question.choices[char.charCodeAt(0) - 97] }} />
                ))}
              </ul>
              ) : 
              question.type === 'numeric' ? (
                <>
                  {studentAnswer} <span dangerouslySetInnerHTML={{ __html: question.units }} />
                </>
              ) :
              studentAnswer
            }
            <div>
              {waiting && <div>Scoring your answer now...</div>}
            </div>
          </span>
          <div>
            {responseHtml && <div dangerouslySetInnerHTML={{ __html: responseHtml }} />}
          </div>
          <div>
            {responseHtml && <button className="btn btn-primary" onClick={handleContinue}>Continue</button>}
          </div>
        </div>
      )}
    </div>
  );
};

export default AnswerForm;